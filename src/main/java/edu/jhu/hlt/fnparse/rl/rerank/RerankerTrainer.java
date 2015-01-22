package edu.jhu.hlt.fnparse.rl.rerank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.params.DecoderBias;
import edu.jhu.hlt.fnparse.rl.params.EmbeddingParams;
import edu.jhu.hlt.fnparse.rl.params.HasUpdate;
import edu.jhu.hlt.fnparse.rl.params.OldFeatureParams;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateful;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateless;
import edu.jhu.hlt.fnparse.util.HingeUpdate;
import edu.jhu.hlt.fnparse.util.MultiTimer;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.hlt.fnparse.rl.rerank.StoppingCondition.*;

/**
 * Training logic can get out of hand (e.g. checking against dev data, etc).
 * Put all that junk in here rather than in Reranker.
 *
 * @author travis
 */
public class RerankerTrainer {
  public static final Logger LOG = Logger.getLogger(RerankerTrainer.class);
  public static boolean SHOW_FULL_EVAL_IN_TUNE = true;

  // General parameters
  private MultiTimer timer = new MultiTimer();
  public final Random rand;
  public StoppingCondition stoppingTrain = new StoppingCondition.HammingConvergence(5.0, 5);
  public StoppingCondition stoppingPretrain = new StoppingCondition.HammingConvergence(0.1, 3000);
  public int threads = 1;
  public int batchSize = 4;

  // Tuning parameters
  public double propDev = 0.2d;
  public int maxDev = 40;
  public StdEvalFunc objective = BasicEvaluation.argOnlyMicroF1;
  public double recallBiasLo = -1, recallBiasHi = 1;
  public int tuneSteps = 5;
  public boolean tuneOnTrainingData = false;

  // Model parameters
  public int beamSize = 1;
  public Params.Stateful statefulParams = Stateful.NONE;
  public Params.Stateless statelessParams = Stateless.NONE;

  public RerankerTrainer(Random rand) {
    this.rand = rand;
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
  private double tuneModelForF1(Reranker model, ItemProvider dev) {
    // Insert the bias into the model
    Params.Stateless theta = model.getStatelessParams();
    DecoderBias bias = new DecoderBias();
    model.setStatelessParams(new Params.SumStateless(bias, theta));

    // Compute the log-spaced bias values to try
    assert recallBiasLo < recallBiasHi;
    assert tuneSteps > 1;
    double step = (recallBiasHi - recallBiasLo) / (tuneSteps - 1);  // linear steps

    // Sweep the bias for the best performance.
    double bestPerf = 0d;
    double bestRecallBias = 0d;
    for (int i = 0; i < tuneSteps; i++) {
      timer.start("tuneModelForF1.eval");
      bias.setRecallBias(recallBiasLo + step * i);
      Map<String, Double> results = eval(model, dev, SHOW_FULL_EVAL_IN_TUNE
          ? String.format("[tune recallBias=%.2f]", bias.getRecallBias()) : null);
      double perf = results.get(objective.getName());
      if (i == 0 || perf > bestPerf) {
        bestPerf = perf;
        bestRecallBias = bias.getRecallBias();
      }
      LOG.info(String.format("[tuneModelForF1] recallBias=%+5.2f perf=%.3f",
          bias.getRecallBias(), perf));
      timer.stop("tuneModelForF1.eval");
    }
    LOG.info("[tuneModelForF1] chose recallBias=" + bestRecallBias
        + " with " + objective.getName() + "=" + bestPerf);
    bias.setRecallBias(bestRecallBias);
    return bestPerf;
  }

  /** Trains and tunes a full model */
  public Reranker train(ItemProvider ip) {
    if (statefulParams == Stateful.NONE && statelessParams == Stateless.NONE)
      throw new IllegalStateException("you need to set the params");

    // Split the data
    ItemProvider train, dev;
    if (tuneOnTrainingData) {
      LOG.info("[train] tuneOnTrainingData=true");
      train = ip;
      dev = new ItemProvider.Slice(ip, Math.min(ip.size(), maxDev), rand);
    } else {
      LOG.info("[train] tuneOnTrainingData=false, splitting data");
      ItemProvider.TrainTestSplit trainDev =
          new ItemProvider.TrainTestSplit(ip, propDev, maxDev, rand);
      train = trainDev.getTrain();
      dev = trainDev.getTest();
    }
    LOG.info("[train] nTrain=" + train.size() + " nDev=" + dev.size());

    // Train the model
    LOG.info("[train] training original model on Hamming loss");
    Reranker m = new Reranker(statefulParams, statelessParams, beamSize, rand);
    try {
      // Stateless training
      hammingTrain(m, train, true);

      // Stateful training
      if (m.getStatefulParams() != Stateful.NONE)
        hammingTrain(m, train, false);
      else
        LOG.info("[train] skipping Stateful train because no Stateful params were given");

    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // Tune the model
    LOG.info("[train] tuning model for F1 loss");
    tuneModelForF1(m, dev);

    LOG.info("[train] times:\n" + timer);
    return m;
  }

  /**
   * Trains the model to minimize hamming loss. If onlyStatelss is true, then
   * Stateful params of the model will not be fit, but updates should be much
   * faster to solve for as they don't require any forwards/backwards pass.
   */
  public void hammingTrain(Reranker r, ItemProvider ip, boolean onlyStateless)
      throws InterruptedException, ExecutionException {
    StoppingCondition stoppingCond = onlyStateless ? stoppingPretrain : stoppingTrain;
    LOG.info("[hammingTrain] batchSize=" + batchSize
        + " threads=" + threads
        + " onlyStateless=" + onlyStateless
        + " stopping=" + stoppingCond);
    String timerStr = "hammingTrain." + (onlyStateless ? "stateless" : "full");
    timer.start(timerStr);

    ExecutorService es = null;
    if (threads > 1)
      es = Executors.newWorkStealingPool(threads);
    int interval = 1000;
    boolean showTime = false;
    boolean showViolation = true;
    for (int iter = 0; true; iter++) {
      double violation = hammingTrainBatch(r, es, ip, onlyStateless, timerStr);
      if (stoppingCond.stop(iter, violation)) {
        LOG.info("[hammingTrain] stopping due to " + stoppingCond);
        break;
      }

      if (iter % interval == 0) {
        if (showViolation)
          LOG.info("[hammingTrain] iter=" + iter + " violation=" + violation);
        if (showTime) {
          Timer t = timer.get(timerStr + ".batch", false);
          int totalUpdates = stoppingCond.estimatedNumberOfIterations();
          LOG.info(String.format(
              "[hammingTrain] estimate: completed %d of %d updates, %.1f minutes remaining",
              t.getCount(), totalUpdates, t.minutesUntil(totalUpdates)));
        }
      }
    }
    if (es != null)
      es.shutdown();

    LOG.info("[hammingTrain] telling Params that training is over");
    r.getStatefulParams().doneTraining();
    r.getStatelessParams().doneTraining();

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
      boolean onlyStateless,
      String timerStrPartial) throws InterruptedException, ExecutionException {
    String timerStr = timerStrPartial + ".batch";
    Timer t = timer.get(timerStr, true).setPrintInterval(30);
    t.start();
    boolean verbose = false;
    int n = ip.size();
    List<HingeUpdate> finishedUpdates = new ArrayList<>();
    if (es == null) {
      if (verbose)
        LOG.info("[hammingTrainBatch] running serial");
      for (int i = 0; i < batchSize; i++) {
        int idx = rand.nextInt(n);
        FNParse y = ip.label(idx);
        List<Item> rerank = ip.items(idx);
        State init = State.initialState(y, rerank);
        if (verbose)
          LOG.info("[hammingTrainBatch] submitting " + idx);
        HingeUpdate u = onlyStateless
          ? r.getStatelessUpdate(init, y)
          : r.getFullUpdate(init, y);
        finishedUpdates.add(u);
      }
    } else {
      if (verbose)
        LOG.info("[hammingTrainBatch] running with ExecutorService");
      List<Future<HingeUpdate>> updates = new ArrayList<>();
      for (int i = 0; i < batchSize; i++) {
        int idx = rand.nextInt(n);
        FNParse y = ip.label(idx);
        List<Item> rerank = ip.items(idx);
        if (verbose)
          LOG.info("[hammingTrainBatch] submitting " + idx);
        updates.add(es.submit(() -> r.getFullUpdate(State.initialState(y, rerank), y)));
      }
      for (Future<HingeUpdate> u : updates)
        finishedUpdates.add(u.get());
    }
    if (verbose)
      LOG.info("[hammingTrainBatch] applying updates");
    assert finishedUpdates.size() == batchSize;

    HingeUpdate.Batch ub = new HingeUpdate.Batch(finishedUpdates);
    List<HasUpdate> batch = Arrays.asList(ub);
    r.getStatefulParams().update(batch);
    r.getStatelessParams().update(batch);

    double violation = ub.violation();
    t.stop();
    return violation;
  }

  public static void main(String[] args) {
    boolean useFeatureHashing = false;
    boolean testOnTrain = false;

    Random rand = new Random(9001);
    RerankerTrainer trainer = new RerankerTrainer(rand);
    ItemProvider ip = Reranker.getItemProvider(10, false);

    ItemProvider train, test;
    if (testOnTrain) {
      train = ip;
      test = ip;
      trainer.tuneOnTrainingData = true;
      trainer.maxDev = 10;
    } else {
      ItemProvider.TrainTestSplit trainTest =
          new ItemProvider.TrainTestSplit(ip, 0.25, 100, rand);
      train = trainTest.getTrain();
      test = trainTest.getTest();
    }

    LOG.info("[main] nTrain=" + train.size() + " nTest=" + test.size() + " testOnTrain=" + testOnTrain);

    Reranker.LOG_FORWARD_SEARCH = false;
    OldFeatureParams.AVERAGE_FEATURES = false;
    OldFeatureParams.SHOW_ON_UPDATE = false;
    trainer.batchSize = 4;
    trainer.beamSize = 1;
    trainer.stoppingPretrain = new Conjunction(
        new HammingConvergence(0.05, 5000), new Time(60));

    if (useFeatureHashing)
      trainer.statelessParams = new OldFeatureParams(featureTemplates, 250 * 1000);
    else
      trainer.statelessParams = new OldFeatureParams(featureTemplates).sizeHint(250 * 1000);
//    trainer.statelessParams = new EmbeddingParams(2);

    Reranker model = trainer.train(train);
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
