package edu.jhu.hlt.fnparse.rl.rerank;

import java.util.ArrayList;
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
import edu.jhu.hlt.fnparse.rl.params.DecoderBias;
import edu.jhu.hlt.fnparse.rl.params.OldFeatureParams;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateful;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateless;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
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

  // General parameters
  private MultiTimer timer = new MultiTimer();
  public final Random rand;
  public int epochs = 1;
  public int threads = 1;
  public int batchSize = 4;

  // Tuning parameters
  public double propDev = 0.25d;
  public int maxDev = 100;
  public StdEvalFunc objective = BasicEvaluation.argOnlyMicroF1;
  public double recallBiasLo = -5, recallBiasHi = 5;
  public int tuneSteps = 7;

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

  /** Inserts an extra bias Param.Stateless into the given model and tunes it */
  private void tuneModelForF1(Reranker model, ItemProvider dev) {
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
          ? "[tune recallBias=" + bias.getRecallBias() + "]" : null);
      double perf = results.get(objective.getName());
      if (i == 0 || perf > bestPerf) {
        bestPerf = perf;
        bestRecallBias = bias.getRecallBias();
      }
      LOG.info(String.format("[tuneModelFoF1] recallBias=%+5.2f perf=%.3f",
          bias.getRecallBias(), perf));
      timer.stop("tuneModelForF1.eval");
    }
    LOG.info("[tuneModelForF1] chose recallBias=" + bestRecallBias
        + " with " + objective.getName() + "=" + bestPerf);
    bias.setRecallBias(bestRecallBias);
  }

  /** Trains and tunes a full model */
  public Reranker train(ItemProvider ip) {
    if (statefulParams == Stateful.NONE && statelessParams == Stateless.NONE)
      throw new IllegalStateException("you need to set the params");

    // Split the data
    LOG.info("[train] starting, splitting data");
    ItemProvider.TrainTestSplit trainDev =
        new ItemProvider.TrainTestSplit(ip, propDev, maxDev, rand);
    ItemProvider train = trainDev.getTrain();
    ItemProvider dev = trainDev.getTest();
    LOG.info("[train] nTrain=" + train.size() + " nDev=" + dev.size());

    // Train the model
    LOG.info("[train] training original model on Hamming loss");
    Reranker m;
    try {
      m = hammingTrain(train);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // Tune the model
    LOG.info("[train] tuning model for F1 loss");
    tuneModelForF1(m, dev);

    LOG.info("[train] times:\n" + timer);
    return m;
  }

  public Reranker hammingTrain(ItemProvider ip) throws InterruptedException, ExecutionException {
    LOG.info("[hammingTrain] batchSize=" + batchSize + " epochs=" + epochs + " threads=" + threads);
    timer.start("hammingTrain");

    // If you don't make an update after this many batches, exit learning early
    final int inARow = 5;

    Reranker r = new Reranker(statefulParams, statelessParams, beamSize);
    ExecutorService es = null;
    if (threads > 1)
      es = Executors.newWorkStealingPool(threads);
    final int n = ip.size();
    final boolean showTime = true;
    final int totalUpdates = (n / batchSize) * epochs;
    int curRun = 0;
    for (int epoch = 0; epoch < epochs; epoch++) {
      int updatedThisEpoch = 0;
      LOG.info("[hammingTrain] startring epoch " + (epoch+1) + "/" + epochs
          + " which will have " + (n/batchSize) + " updates");
      for (int i = 0; i < n; i += batchSize) {
        int u = hammingTrainBatch(r, es, ip);
        updatedThisEpoch += u;
        System.out.print("*");
        if (u == 0) {
          curRun++;
          if (curRun == inARow) {
            System.out.println();
            LOG.info("[hammingTrain] exiting early in the middle of epoch " + (epoch+1)
                + " of " + epochs + " because we made an entire pass over " + n
                + " data points without making a single subgradient step");
            break;
          }
        } else {
          curRun = 0;
        }
        if (showTime) {
          Timer t = timer.get("hammingTrainBatch");
          LOG.info("[hammingTrain] completed " + t.getCount() + " of "
              + totalUpdates + ", estimated "
              + t.minutesUntil(totalUpdates) + " minutes remaining");
        }
      }

        System.out.println();
        LOG.info("[hammingTrain] updated " + updatedThisEpoch);
      }
      if (es != null)
        es.shutdown();

    LOG.info("[hammingTrain] times:\n" + timer);
    timer.stop("hammingTrain");
    return r;
  }

  private int hammingTrainBatch(Reranker r, ExecutorService es, ItemProvider ip) throws InterruptedException, ExecutionException {
    Timer t = timer.get("hammingTrainBatch", true);
    t.setPrintInterval(1);
    t.start();
    boolean verbose = false;
    int n = ip.size();
    List<Update> finishedUpdates = new ArrayList<>();
    if (es == null) {
      LOG.info("[hammingTrainBatch] running serial");
      for (int i = 0; i < batchSize; i++) {
        int idx = rand.nextInt(n);
        FNParse y = ip.label(idx);
        List<Item> rerank = ip.items(idx);
        if (verbose)
          LOG.info("[hammingTrainBatch] submitting " + idx);
        finishedUpdates.add(r.new Update(y, rerank));
      }
    } else {
      LOG.info("[hammingTrainBatch] running with ExecutorService");
      List<Future<Update>> updates = new ArrayList<>();
      for (int i = 0; i < batchSize; i++) {
        int idx = rand.nextInt(n);
        FNParse y = ip.label(idx);
        List<Item> rerank = ip.items(idx);
        if (verbose)
          LOG.info("[hammingTrainBatch] submitting " + idx);
        updates.add(es.submit(() -> r.new Update(y, rerank)));
      }
      for (Future<Update> u : updates)
        finishedUpdates.add(u.get());
    }
    if (verbose)
      LOG.info("[hammingTrainBatch] applying updates");
    assert finishedUpdates.size() == batchSize;
    int updated = 0;
    for (Update u : finishedUpdates) {
      if (u.apply(batchSize))
        updated++;
    }
    t.stop();
    return updated;
  }

  public static void main(String[] args) {
    // Create a train-test split
    LOG.info("[main] starting, splitting data");
    Random rand = new Random(9001);
    ItemProvider.TrainTestSplit trainTest =
        new ItemProvider.TrainTestSplit(Reranker.getItemProvider(), 0.25, 100, rand);
    ItemProvider train = trainTest.getTrain();
    ItemProvider test = trainTest.getTest();
    LOG.info("[main] nTrain=" + train.size() + " nTest=" + test.size());

    boolean useFeatureHashing = true;
    RerankerTrainer trainer = new RerankerTrainer(rand);
    trainer.beamSize = 1;
    if (useFeatureHashing)
      trainer.statelessParams = new OldFeatureParams(featureTemplates, 250 * 1000);
    else
      trainer.statelessParams = new OldFeatureParams(featureTemplates).sizeHint(250 * 1000);
    Reranker model = trainer.train(train);

    LOG.info("[main] done training, evaluating");
    eval(model, test, "[main]");
  }
  private static final String featureTemplates =
      "frameRole * 1"
      + " + frameRoleArg * 1"
      + " + role * 1"
      + " + roleArg * 1"
      + " + frameRole * head1Lemma"
      + " + frameRole * head1ParentLemma"
      + " + frameRole * head1WordWnSynset"
      + " + frameRole * head1Shape"
      + " + frameRole * head1Pos"
      + " + frameRole * span1StanfordRule"
      + " + roleArg * span1PosPat-COARSE_POS-1-1"
      + " + roleArg * span1PosPat-WORD_SHAPE-1-1"
      + " + arg * span1PosPat-COARSE_POS-1-1"
      + " + arg * span1PosPat-WORD_SHAPE-1-1"
      + " + frameRole * span1span2Overlap"
      + " + frameRole * Dist(SemaforPathLengths,Head1,Head2)";
}
