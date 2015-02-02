package edu.jhu.hlt.fnparse.rl.rerank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.params.ActionTypeParams;
import edu.jhu.hlt.fnparse.rl.params.DecoderBias;
import edu.jhu.hlt.fnparse.rl.params.EmbeddingParams;
import edu.jhu.hlt.fnparse.rl.params.GlobalFeature;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateful;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateless;
import edu.jhu.hlt.fnparse.rl.params.TemplatedFeatureParams;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.util.LearningRateSchedule;
import edu.jhu.hlt.fnparse.util.MultiTimer;
import edu.jhu.hlt.fnparse.util.Timer;

/**
 * Training logic can get out of hand (e.g. checking against dev data, etc).
 * Put all that junk in here rather than in Reranker.
 *
 * @author travis
 */
public class RerankerTrainer {
  public static final Logger LOG = Logger.getLogger(RerankerTrainer.class);
  public static boolean SHOW_FULL_EVAL_IN_TUNE = true;

  // may differ across pretrain/train
  public class Config {
    // Meta
    public final String name;

    // General parameters
    public int threads = 1;
    public int beamSize = 1;
    public int batchSize = 4;
    public StoppingCondition stopping = new StoppingCondition.Conjunction(
        new StoppingCondition.Time(60),
        new StoppingCondition.AvgErrReduction(50, 1e-4, 1e-3));
    public LearningRateSchedule learningRate = new LearningRateSchedule.Normal(1);
    public Consumer<Integer> calledEveryEpoch = i -> {};

    // Tuning parameters
    public boolean performTuning() { return propDev > 0 && maxDev > 0; }
    public void dontPerformTuning() { propDev = 0; maxDev = 0; }
    public double propDev = 0.2d;
    public int maxDev = 40;
    public StdEvalFunc objective = BasicEvaluation.argOnlyMicroF1;
    public double recallBiasLo = -1, recallBiasHi = 1;
    public int tuneSteps = 5;
    public boolean tuneOnTrainingData = false;

    public Config(String name) {
      this.name = name;
    }

    public String toString() {
      return "(Config " + name
          + " threads=" + threads
          + " beam=" + beamSize
          + " batch=" + batchSize
          + " stopping=" + stopping
          + " learningRate=" + learningRate
          + " tune=" + performTuning()
          + " objective=" + objective.getName()
          + " propDev=" + propDev
          + " maxDev=" + maxDev
          + " tuneSteps=" + tuneSteps
          + " tuneOnTrain=" + tuneOnTrainingData
          + ")";
    }
  }

  // Configuration
  private MultiTimer timer = new MultiTimer();
  public final Random rand;
  public Config pretrainConf; // for training statelessParams
  public Config trainConf;    // for training statelessParams + statefulParams

  // Model parameters
  public Params.Stateful statefulParams = Stateful.NONE;
  public Params.Stateless statelessParams = Stateless.NONE;

  public RerankerTrainer(Random rand) {
    this.rand = rand;
    this.pretrainConf = new Config("pretrain");
    this.pretrainConf.beamSize = 1;
    this.pretrainConf.tuneOnTrainingData = true;
    this.trainConf = new Config("train");
  }

  public void addParams(Params.Stateful p) {
    if (this.statefulParams == Params.Stateful.NONE)
      this.statefulParams = p;
    else
      this.statefulParams = new Params.SumStateful(this.statefulParams, p);
  }

  public void addParams(Params.Stateless p) {
    if (this.statelessParams == Params.Stateless.NONE)
      this.statelessParams = p;
    else
      this.statelessParams = new Params.SumStateless(this.statelessParams, p);
  }

  /** If you don't want anything to print, just provide showStr=null */
  public static Map<String, Double> eval(Reranker m, ItemProvider ip, String showStr) {
    List<FNParse> y = ItemProvider.allLabels(ip);
    List<FNTagging> f = DataUtil.convertParsesToTaggings(y);
    List<FNParse> yHat = m.predict(f);
    Map<String, Double> results = BasicEvaluation.evaluate(y, yHat);
    if (showStr != null)
      BasicEvaluation.showResults(showStr, results);
    return results;
  }

  public static double eval(Reranker m, ItemProvider ip, StdEvalFunc objective) {
    String showStr = null;
    Map<String, Double> results = eval(m, ip, showStr);
    return results.get(objective.getName());
  }

  /**
   * Inserts an extra bias Param.Stateless into the given model and tunes it
   * @return the F1 on the dev set of the selected recall bias.
   */
  private double tuneModelForF1(Reranker model, Config conf, ItemProvider dev) {
    // Insert the bias into the model
    Params.Stateless theta = model.getStatelessParams();
    DecoderBias bias = new DecoderBias();
    model.setStatelessParams(new Params.SumStateless(bias, theta));

    // Compute the log-spaced bias values to try
    assert conf.recallBiasLo < conf.recallBiasHi;
    assert conf.tuneSteps > 1;
    double step = (conf.recallBiasHi - conf.recallBiasLo) / (conf.tuneSteps - 1);  // linear steps

    // Sweep the bias for the best performance.
    double bestPerf = 0d;
    double bestRecallBias = 0d;
    for (int i = 0; i < conf.tuneSteps; i++) {
      timer.start("tuneModelForF1.eval");
      bias.setRecallBias(conf.recallBiasLo + step * i);
      Map<String, Double> results = eval(model, dev, SHOW_FULL_EVAL_IN_TUNE
          ? String.format("[tune recallBias=%.2f]", bias.getRecallBias()) : null);
      double perf = results.get(conf.objective.getName());
      if (i == 0 || perf > bestPerf) {
        bestPerf = perf;
        bestRecallBias = bias.getRecallBias();
      }
      LOG.info(String.format("[tuneModelForF1] recallBias=%+5.2f perf=%.3f",
          bias.getRecallBias(), perf));
      timer.stop("tuneModelForF1.eval");
    }
    LOG.info("[tuneModelForF1] chose recallBias=" + bestRecallBias
        + " with " + conf.objective.getName() + "=" + bestPerf);
    bias.setRecallBias(bestRecallBias);
    return bestPerf;
  }

  /** Trains and tunes a full model */
  public Reranker train1(ItemProvider ip) {
    if (statefulParams == Stateful.NONE && statelessParams == Stateless.NONE)
      throw new IllegalStateException("you need to set the params");

    LOG.info("[train1] local train");
    Reranker m = new Reranker(
        Params.Stateful.NONE, statelessParams, pretrainConf.beamSize, rand);
    train2(m, ip, pretrainConf);

    LOG.info("[train1] global train");
    if (statefulParams != Params.Stateful.NONE) {
      m.setStatefulParams(statefulParams);
      m.setBeamWidth(trainConf.beamSize);
      train2(m, ip, trainConf);
    } else {
      LOG.info("[train1] skipping global train because there are no stateful params");
    }

    LOG.info("[train1] done, times:\n" + timer);
    return m;
  }

  public void train2(Reranker m, ItemProvider ip, Config conf) {
    // Split the data
    ItemProvider train, dev;
    if (conf.tuneOnTrainingData) {
      LOG.info("[train] tuneOnTrainingData=true");
      train = ip;
      dev = new ItemProvider.Slice(ip, Math.min(ip.size(), conf.maxDev), rand);
    } else {
      LOG.info("[train] tuneOnTrainingData=false, splitting data");
      ItemProvider.TrainTestSplit trainDev =
          new ItemProvider.TrainTestSplit(ip, conf.propDev, conf.maxDev, rand);
      train = trainDev.getTrain();
      dev = trainDev.getTest();
    }
    LOG.info("[train] nTrain=" + train.size() + " nDev=" + dev.size());

    try {
      // Train the model
      hammingTrain(m, train, conf);

      // Tune the model
      if (conf.performTuning()) {
        tuneModelForF1(m, conf, dev);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    LOG.info("[train2] done, times:\n" + timer);
  }

  /**
   * Trains the model to minimize hamming loss. If onlyStatelss is true, then
   * Stateful params of the model will not be fit, but updates should be much
   * faster to solve for as they don't require any forwards/backwards pass.
   */
  public void hammingTrain(Reranker r, ItemProvider ip, Config conf)
      throws InterruptedException, ExecutionException {
    LOG.info("[hammingTrain] starting, conf=" + conf);
    String timerStr = "hammingTrain." + conf.name;
    timer.start(timerStr);

    ExecutorService es = null;
    if (conf.threads > 1)
      es = Executors.newWorkStealingPool(conf.threads);
    int interval = 1;
    boolean showTime = false;
    boolean showViolation = true;
    outer:
    for (int iter = 0; true; ) {
      for (int i = 0; i < ip.size(); i += conf.batchSize) {
        iter++;
        double violation = hammingTrainBatch(r, es, ip, conf, iter, timerStr);
        if (conf.stopping.stop(iter, violation)) {
          LOG.info("[hammingTrain] stopping due to " + conf.stopping);
          break outer;
        }

        if (iter % interval == 0) {
          if (showViolation)
            LOG.info("[hammingTrain] iter=" + iter + " violation=" + violation);
          if (showTime) {
            Timer t = timer.get(timerStr + ".batch", false);
            int totalUpdates = conf.stopping.estimatedNumberOfIterations();
            LOG.info(String.format(
                "[hammingTrain] estimate: completed %d of %d updates, %.1f minutes remaining",
                t.getCount(), totalUpdates, t.minutesUntil(totalUpdates)));
          }
        }
      }
      conf.calledEveryEpoch.accept(iter);
    }
    if (es != null)
      es.shutdown();

    LOG.info("[hammingTrain] telling Params that training is over");
    r.getStatelessParams().doneTraining();
    r.getStatefulParams().doneTraining();

    LOG.info("[hammingTrain] times:\n" + timer);
    timer.stop(timerStr);
  }

  /**
   * Returns the average violation over this batch.
   */
  private double hammingTrainBatch(
      Reranker r,
      ExecutorService es,
      ItemProvider ip,
      Config conf,
      int iter,
      String timerStrPartial) throws InterruptedException, ExecutionException {
    Timer tmv = timer.get(timerStrPartial + ".mostViolated", true).setPrintInterval(10).ignoreFirstTime();
    Timer to = timer.get(timerStrPartial + ".oracle", true).setPrintInterval(10).ignoreFirstTime();
    Timer t = timer.get(timerStrPartial + ".batch", true).setPrintInterval(10).ignoreFirstTime();
    t.start();
    boolean verbose = false;
    int n = ip.size();
    List<Update> finishedUpdates = new ArrayList<>();
    if (es == null) {
      if (verbose)
        LOG.info("[hammingTrainBatch] running serial");
      for (int i = 0; i < conf.batchSize; i++) {
        int idx = rand.nextInt(n);
        FNParse y = ip.label(idx);
        List<Item> rerank = ip.items(idx);
        State init = State.initialState(y, rerank);
        if (verbose)
          LOG.info("[hammingTrainBatch] submitting " + idx);
        Update u = r.getStatefulParams() == Params.Stateful.NONE
          ? r.getStatelessUpdate(init, y)
          : r.getFullUpdate(init, y, rand, to, tmv);
        finishedUpdates.add(u);
      }
    } else {
      if (verbose)
        LOG.info("[hammingTrainBatch] running with ExecutorService");
      List<Future<Update>> updates = new ArrayList<>();
      for (int i = 0; i < conf.batchSize; i++) {
        int idx = rand.nextInt(n);
        FNParse y = ip.label(idx);
        List<Item> rerank = ip.items(idx);
        if (verbose)
          LOG.info("[hammingTrainBatch] submitting " + idx);
        updates.add(es.submit(() ->
          r.getFullUpdate(State.initialState(y, rerank), y, rand, null, null)));
      }
      for (Future<Update> u : updates)
        finishedUpdates.add(u.get());
    }
    if (verbose)
      LOG.info("[hammingTrainBatch] applying updates");
    assert finishedUpdates.size() == conf.batchSize;

    // Apply the update
    double learningRate = conf.learningRate.learningRate();
    double violation = new Update.Batch<>(finishedUpdates).apply(learningRate);
    conf.learningRate.observe(iter, violation, conf.batchSize);
    t.stop();
    return violation;
  }

  public static void main(String[] args) {
    boolean useGlobalFeatures = true;
    boolean useEmbeddingParams = false; // else use TemplatedFeatureParams
    boolean useEmbeddingParamsDebug = false;
    boolean useFeatureHashing = false;
    boolean testOnTrain = false;

    Random rand = new Random(9001);
    RerankerTrainer trainer = new RerankerTrainer(rand);
    ItemProvider ip = new ItemProvider.ParseWrapper(DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences())
        .subList(0, 100));

    // Show how many roles we need to make predictions for (in train and test)
    for (int i = 0; i < ip.size(); i++) {
      State s = State.initialState(ip.label(i));
      LOG.info("TK=" + s.numFrameRoleInstances());
    }

    // Split into train and test sets
    ItemProvider train, test;
    if (testOnTrain) {
      train = ip;
      test = ip;
      trainer.pretrainConf.tuneOnTrainingData = true;
      trainer.pretrainConf.maxDev = 15;
      trainer.trainConf.tuneOnTrainingData = true;
      trainer.trainConf.maxDev = 15;
    } else {
      ItemProvider.TrainTestSplit trainTest =
          new ItemProvider.TrainTestSplit(ip, 0.25, 10, rand);
      train = trainTest.getTrain();
      test = trainTest.getTest();
    }

    LOG.info("[main] nTrain=" + train.size() + " nTest=" + test.size() + " testOnTrain=" + testOnTrain);

    // On the last run, the average error for the first and second 100
    // violations respectively were 0.0445782 and 0.0263481.
    // This comes out to
    // avgRedPerIter = (0.0445782 - 0.0263481) / 100 = 0.000182301
    // relRedPerIter = ((0.0445782 - 0.0263481) / 0.0445782) / 100 = 0.004089465254317132
//    trainer.pretrainConf.stopping =
//        new StoppingCondition.AvgErrReduction(10, 1e-4, 1e-3);
    trainer.pretrainConf.learningRate = new LearningRateSchedule.Normal(1, 50, 0.5);

    // For full training avg err for the first and second 50 violations
    // respectively were 33.2382 and 10.3588
    // avgRedPerIter = (33.2382 - 10.3588) / 50 = 0.45758799999999994
    // relRedPerIter = ((33.2382 - 10.3588) / 33.2382) / 50 = 0.013766930820561882
//    trainer.trainConf.stopping =
//        new StoppingCondition.AvgErrReduction(50, 1e-1, 1e-2);
    trainer.trainConf.learningRate = new LearningRateSchedule.Normal(1, 50, 0.5);

    final int hashBuckets = 8 * 1000 * 1000;
    final double l2Penalty = 1e-8;
    if (useEmbeddingParams) {
      int embeddingSize = 2;
      EmbeddingParams ep = new EmbeddingParams(embeddingSize, l2Penalty, trainer.rand);
      ep.learnTheta(true);
      if (useEmbeddingParamsDebug)
        ep.debug(new TemplatedFeatureParams(featureTemplates, hashBuckets), l2Penalty);
      trainer.addParams(ep);
    } else {
      if (useFeatureHashing) {
        trainer.addParams(new TemplatedFeatureParams(featureTemplates, l2Penalty, hashBuckets));
      } else {
        trainer.addParams(new TemplatedFeatureParams(featureTemplates, l2Penalty));
      }

      trainer.addParams(new ActionTypeParams(l2Penalty));
    }

    if (useGlobalFeatures) {
      double globalL2Penalty = 1;
      double globalLearningRate = 0.1;
//      GlobalFeature.RoleCooccurenceFeatureStateful g1 =
//          new GlobalFeature.RoleCooccurenceFeatureStateful(globalL2Penalty, globalLearningRate);
//      g1.setShowOnUpdate();
      GlobalFeature.ArgOverlapFeature g2 =
          new GlobalFeature.ArgOverlapFeature(globalL2Penalty, globalLearningRate);
      g2.setShowOnUpdate();
      GlobalFeature.SpanBoundaryFeature g3 =
          new GlobalFeature.SpanBoundaryFeature(globalL2Penalty, globalLearningRate);
      g3.setShowOnUpdate();
//      trainer.statefulParams = new Params.SumStateful(
//          g1, new Params.SumStateful(g2, g3));
      trainer.statefulParams = new Params.SumStateful(g2, g3);
    }

    Reranker model = trainer.train1(train);
    LOG.info("[main] done training, evaluating");
    eval(model, test, "[main]");
  }

  private static final String featureTemplates = "1"
      + " + frameRole * 1"
      + " + frameRoleArg * 1"
      + " + role * 1"
      + " + roleArg * 1"

      + " + frameRole * span1FirstWord"
      + " + frameRole * span1FirstShape"
      + " + frameRole * span1FirstPos"

      + " + frameRole * span1LastWord"
      + " + frameRole * span1LastShape"
      + " + frameRole * span1LastPos"

      + " + frameRole * span1LeftShape"
      + " + frameRole * span1LeftPos"

      + " + frameRole * span1RightShape"
      + " + frameRole * span1RightPos"

      + " + frameRole * head1Lemma"
      + " + frameRole * head1ParentLemma"
      + " + frameRole * head1WordWnSynset"
      + " + frameRole * head1Shape"
      + " + frameRole * head1Pos"

      + " + frameRole * span1PosPat-COARSE_POS-1-1"
      + " + frameRole * span1PosPat-WORD_SHAPE-1-1"

      + " + frameRole * span1StanfordCategory"
      + " + frameRole * span1StanfordRule"

      + " + frameRole * head1CollapsedParentDir"
      + " + frameRole * head1CollapsedLabel"

      + " + frameRole * head1head2Path-POS-DEP-t"
      + " + role * head1head2Path-POS-DEP-t"

      /* These seem to hurt performance ???
      + " + roleArg * span1PosPat-COARSE_POS-1-1"
      + " + roleArg * span1PosPat-WORD_SHAPE-1-1"
      + " + arg * span1PosPat-COARSE_POS-1-1"
      + " + arg * span1PosPat-WORD_SHAPE-1-1"
      */
      + " + frameRole * span1span2Overlap"
      + " + frameRole * Dist(SemaforPathLengths,Head1,Head2)";
}
