package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.experiment.grid.ResultReporter;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.params.DecoderBias;
import edu.jhu.hlt.fnparse.rl.params.EmbeddingParams;
import edu.jhu.hlt.fnparse.rl.params.GlobalFeature;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateful;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateless;
import edu.jhu.hlt.fnparse.rl.params.TemplatedFeatureParams;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.util.ExperimentProperties;
import edu.jhu.hlt.fnparse.util.LearningRateSchedule;
import edu.jhu.hlt.fnparse.util.MultiTimer;
import edu.jhu.hlt.fnparse.util.TimeMarker;
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
    public StoppingCondition stopping = new StoppingCondition.Time(4 * 60);
    public LearningRateSchedule learningRate = new LearningRateSchedule.Normal(1);

    // Tuning parameters
    public double propDev = 0.2d;
    public int maxDev = 50;
    public StdEvalFunc objective = BasicEvaluation.argOnlyMicroF1;
    public double recallBiasLo = -1, recallBiasHi = 1;
    public int tuneSteps = 5;
    public boolean tuneOnTrainingData = false;

    // Convenient extras
    public Consumer<Integer> calledEveryEpoch = i -> {};
    public boolean performTuning() { return propDev > 0 && maxDev > 0; }
    public void dontPerformTuning() { propDev = 0; maxDev = 0; }
    public void spreadTuneRange(double factor) {
      assert factor > 0;
      assert recallBiasLo < 0;
      assert recallBiasHi > 0;
      recallBiasLo *= factor;
      recallBiasHi *= factor;
    }

    public Config(String name) {
      this.name = name;
    }

    /**
     * After this method, will stop if the previous stopping condition says so
     * OR if the given stopping condition does.
     * @return what is passed in.
     */
    public <T extends StoppingCondition> T addStoppingCondition(T s) {
      stopping = new StoppingCondition.Conjunction(stopping, s);
      return s;
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
  public List<ResultReporter> reporters;
  public Config pretrainConf; // for training statelessParams
  public Config trainConf;    // for training statelessParams + statefulParams

  // Model parameters
  public Params.Stateful statefulParams = Stateful.NONE;
  public Params.Stateless statelessParams = Stateless.NONE;

  public RerankerTrainer(Random rand) {
    this.rand = rand;
    this.pretrainConf = new Config("pretrain");
    this.pretrainConf.beamSize = 1;
    this.trainConf = new Config("train");
    this.trainConf.spreadTuneRange(2);
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
//    train2(m, ip, pretrainConf);

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

  /**
   * Adds a stopping condition based on the dev set performance.
   */
  public void train2(Reranker m, ItemProvider ip, Config conf) {
    // Split the data
    final ItemProvider train, dev;
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

    // Use dev data for stopping condition
    File rScript = new File("scripts/stop.sh");
    double alpha = 0.2d;
    double d = 8;
    DoubleSupplier devLossFunc = new DoubleSupplier() {
      @Override
      public double getAsDouble() {
        LOG.info("[devLossFunc] computing dev set loss on " + dev.size() + " examples");
        double loss = 0d;
        for (int i = 0; i < dev.size(); i++) {
          FNParse y = ip.label(i);
          List<Item> rerank = ip.items(i);
          State init = State.initialState(y, rerank);
          Update u = m.hasStatefulFeatures()
              ? m.getFullUpdate(init, y, rand, null, null)
              : m.getStatelessUpdate(init, y);
          loss += u.violation();
        }
        loss /= dev.size();
        LOG.info("[devLossFunc] loss=" + loss + " nDev=" + dev.size());
        return loss;
      }
    };
    StoppingCondition.DevSet dynamicStopping = conf.addStoppingCondition(
        new StoppingCondition.DevSet(rScript, devLossFunc, alpha, d));

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

    // StoppingCondition.DevSet writes to a file, this closes that.
    dynamicStopping.close();
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
    if (conf.threads > 1) {
      String msg = "i'm pretty sure multi-threaded features do not work";
      LOG.warn(msg);
      assert false : msg;
      es = Executors.newWorkStealingPool(conf.threads);
    }
    TimeMarker t = new TimeMarker();
    double secsBetweenUpdates = 3 * 60d;
    boolean showTime = false;
    boolean showViolation = true;
    outer:
    for (int iter = 0; true; ) {
      for (int i = 0; i < ip.size(); i += conf.batchSize) {
        double violation = hammingTrainBatch(r, es, ip, conf, iter, timerStr);
        boolean stop = conf.stopping.stop(iter, violation);
        if (stop) {
          LOG.info("[hammingTrain] stopping due to " + conf.stopping);
          break outer;
        }

        // Print some data every once in a while.
        // Nothing in this conditional should have side-effects on the learning.
        if (t.enoughTimePassed(secsBetweenUpdates)) {
          r.getStatelessParams().showWeights();
          r.getStatefulParams().showWeights();
          if (showViolation)
            LOG.info("[hammingTrain] iter=" + iter + " trainViolation=" + violation);
          if (showTime) {
            Timer bt = timer.get(timerStr + ".batch", false);
            int totalUpdates = conf.stopping.estimatedNumberOfIterations();
            LOG.info(String.format(
                "[hammingTrain] estimate: completed %d of %d updates, %.1f minutes remaining",
                bt.getCount(), totalUpdates, bt.minutesUntil(totalUpdates)));
          }
        }
        iter++;
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
        Update u = r.hasStatefulFeatures()
          ? r.getFullUpdate(init, y, rand, to, tmv)
          : r.getStatelessUpdate(init, y);
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

  /**
   * First arg must be the job name (for tie-ins with tge) and the remaining are
   * key-value pairs.
   */
  public static void main(String[] args) throws IOException {
    assert args.length % 2 == 1;
    String jobName = args[0];
    ExperimentProperties config = new ExperimentProperties();
    //config.putAll(System.getProperties());
    config.putAll(Arrays.copyOfRange(args, 1, args.length), false);
    File workingDir = config.getOrMakeDir("workingDir");
    boolean useGlobalFeatures = config.getBoolean("useGlobalFeatures", true);
    boolean useEmbeddingParams = config.getBoolean("useEmbeddingParams", false); // else use TemplatedFeatureParams
    boolean useEmbeddingParamsDebug = config.getBoolean("useEmbeddingParamsDebug", false);
    boolean useFeatureHashing = config.getBoolean("useFeatureHashing", false);
    boolean testOnTrain = config.getBoolean("testOnTrain", false);

    int nTrain = config.getInt("nTrain", 100);
    Random rand = new Random(9001);
    RerankerTrainer trainer = new RerankerTrainer(rand);
    trainer.reporters = ResultReporter.getReporters(config);
    ItemProvider ip = new ItemProvider.ParseWrapper(DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences())
        .subList(0, nTrain));

    trainer.pretrainConf.batchSize = config.getInt("pretrainBatchSize", 4);
    trainer.trainConf.batchSize = config.getInt("trainBatchSize", 2);

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
      double propTest = 0.25;
      int maxTest = 9999;
      ItemProvider.TrainTestSplit trainTest =
          new ItemProvider.TrainTestSplit(ip, propTest, maxTest, rand);
      train = trainTest.getTrain();
      test = trainTest.getTest();
    }

    LOG.info("[main] nTrain=" + train.size() + " nTest=" + test.size() + " testOnTrain=" + testOnTrain);

    final int hashBuckets = 8 * 1000 * 1000;
    final double l2Penalty = config.getDouble("l2Penalty", 1e-8);
    LOG.info("[main] using l2Penalty=" + l2Penalty);
    if (useEmbeddingParams) {
      LOG.info("[main] using embedding params");
      int embeddingSize = 2;
      EmbeddingParams ep = new EmbeddingParams(embeddingSize, l2Penalty, trainer.rand);
      ep.learnTheta(true);
      if (useEmbeddingParamsDebug)
        ep.debug(new TemplatedFeatureParams(featureTemplates, hashBuckets), l2Penalty);
      trainer.addParams(ep);
    } else {
      if (useFeatureHashing) {
        LOG.info("[main] using TemplatedFeatureParams with feature hashing");
        trainer.addParams(new TemplatedFeatureParams(featureTemplates, l2Penalty, hashBuckets));
      } else {
        LOG.info("[main] using TemplatedFeatureParams with an Alphabet");
        trainer.addParams(new TemplatedFeatureParams(featureTemplates, l2Penalty));
      }

      //trainer.addParams(new ActionTypeParams(l2Penalty));
    }

    if (useGlobalFeatures) {
      double globalL2Penalty = config.getDouble("globalL2Penalty", 1e-2);
      double globalLearningRate = 0.1;
      LOG.info("[main] using global features with l2p=" + globalL2Penalty + " lr=" + globalLearningRate);

//      if (config.getBoolean("useRoleCooc", false)) {
//        trainer.addParams(new GlobalFeature.RoleCooccurenceFeatureStateful(
//            globalL2Penalty, globalLearningRate));
//      }

      trainer.addParams(new GlobalFeature.ArgOverlapFeature(
          globalL2Penalty, globalLearningRate));

//      trainer.addParams(new GlobalFeature.SpanBoundaryFeature(
//          globalL2Penalty, globalLearningRate));
    }

    LOG.info("[main] starting training, config:");
    config.store(System.out, null);
    Reranker model = trainer.train1(train);
    LOG.info("[main] done training, evaluating");
    Map<String, Double> perfResults = eval(model, test, "[main]");
    Map<String, String> results = new HashMap<>();
    results.putAll(ResultReporter.mapToString(perfResults));
    results.putAll(ResultReporter.mapToString(config));

    // Save the configuration
    OutputStream os = new FileOutputStream(new File(workingDir, "config.xml"));
    config.storeToXML(os, "ran on " + new Date());
    os.close();

    // Report results back to tge
    double mainResult = perfResults.get(BasicEvaluation.argOnlyMicroF1.getName());
    for (ResultReporter rr : trainer.reporters)
      rr.reportResult(mainResult, jobName, ResultReporter.mapToString(results));
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
