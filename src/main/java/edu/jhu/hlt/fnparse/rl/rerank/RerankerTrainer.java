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
import edu.jhu.hlt.fnparse.rl.params.DenseFastFeatures;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateless;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.util.MultiTimer;

/**
 * Training logic can get out of hand (e.g. checking against dev data, etc).
 * Put all that junk in here rather than in Reranker.
 *
 * @author travis
 */
public class RerankerTrainer {
  public static final Logger LOG = Logger.getLogger(RerankerTrainer.class);

  // General parameters
  private MultiTimer timer = new MultiTimer();
  public final Random rand;
  public int epochs = 1;
  public int threads = 1;
  public int batchSize = 10;

  // Tuning parameters
  public double propDev = 0.25d;
  public int maxDev = 100;
  public StdEvalFunc objective = BasicEvaluation.argOnlyMicroF1;
  public double recallBiasLo = -5, recallBiasHi = 5;
  public int tuneSteps = 12;

  // Model parameters
  public int beamSize = 50;
  public Params.Stateful statefulParams = new DenseFastFeatures();
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
      bias.setRecallBias(recallBiasLo + step * i);
      double perf = eval(model, dev, objective);
      if (i == 0 || perf > bestPerf) {
        bestPerf = perf;
        bestRecallBias = bias.getRecallBias();
      }
    }
    LOG.info("[tuneModelForF1] chose recallBias=" + bestRecallBias
        + " with " + objective.getName() + "=" + bestPerf);
    bias.setRecallBias(bestRecallBias);
  }

  /** Trains and tunes a full model */
  public Reranker train(ItemProvider ip) {
    // Split the data
    LOG.info("[train] starting, splitting data");
    ItemProvider.TrainTestSplit trainDev =
        new ItemProvider.TrainTestSplit(ip, propDev, maxDev, rand);
    ItemProvider train = trainDev.getTrain();
    ItemProvider dev = trainDev.getTest();
    LOG.info("[train] nTrain=" + train.size() + " nDev=" + dev.size());

    // Train the model
    LOG.info("[train] training original model on Hamming loss");
    Reranker m = hammingTrain(train);

    // Tune the model
    LOG.info("[train] tuning model for F1 loss");
    tuneModelForF1(m, dev);

    return m;
  }

  public Reranker hammingTrain(ItemProvider ip) {
    LOG.info("[train] batchSize=" + batchSize + " epochs=" + epochs + " threads=" + threads);

    // If you don't make an update after this many batches, exit learning early
    final int inARow = 5;

    Reranker r = new Reranker(statefulParams, statelessParams, beamSize);
    ExecutorService es = null;
    if (threads > 1)
      es = Executors.newWorkStealingPool(threads);
    int n = ip.size();
    int curRun = 0;
    try {
      for (int epoch = 0; epoch < epochs; epoch++) {
        int updated = 0;
        LOG.info("[train] startring epoch " + (epoch+1) + "/" + epochs
            + " which will have " + (n/batchSize) + " updates");
        for (int i = 0; i < n; i += batchSize) {
          int u = hammingTrainBatch(r, es, ip);
          updated += u;
          System.out.print("*");
          if (u == 0) {
            curRun++;
            if (curRun == inARow) {
              System.out.println();
              LOG.info("[train] exiting early in the middle of epoch " + (epoch+1)
                  + " of " + epochs + " because we made an entire pass over " + n
                  + " data points without making a single subgradient step");
              break;
            }
          } else {
            curRun = 0;
          }
        }

        System.out.println();
        LOG.info("[train] updated " + updated);
      }
      if (es != null)
        es.shutdown();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    LOG.info("[train] times:\n" + timer);
    return r;
  }

  private int hammingTrainBatch(Reranker r, ExecutorService es, ItemProvider ip) throws InterruptedException, ExecutionException {
    timer.start("trainBatch");
    boolean verbose = false;
    int n = ip.size();
    List<Update> finishedUpdates = new ArrayList<>();
    if (es == null) {
      LOG.info("[trainBatch] running serial");
      for (int i = 0; i < batchSize; i++) {
        int idx = rand.nextInt(n);
        FNParse y = ip.label(idx);
        List<Item> rerank = ip.items(idx);
        if (verbose)
          LOG.info("[trainBatch] submitting " + idx);
        finishedUpdates.add(r.new Update(y, rerank));
      }
    } else {
      LOG.info("[trainBatch] running with ExecutorService");
      List<Future<Update>> updates = new ArrayList<>();
      for (int i = 0; i < batchSize; i++) {
        int idx = rand.nextInt(n);
        FNParse y = ip.label(idx);
        List<Item> rerank = ip.items(idx);
        if (verbose)
          LOG.info("[trainBatch] submitting " + idx);
        updates.add(es.submit(() -> r.new Update(y, rerank)));
      }
      for (Future<Update> u : updates)
        finishedUpdates.add(u.get());
    }
    if (verbose)
      LOG.info("[trainBatch] applying updates");
    assert finishedUpdates.size() == batchSize;
    int updated = 0;
    for (Update u : finishedUpdates) {
      if (u.apply())
        updated++;
    }
    timer.stop("trainBatch");
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

    RerankerTrainer trainer = new RerankerTrainer(rand);
    Reranker model = trainer.train(train);

    LOG.info("[main] done training, evaluating");
    eval(model, test, "[main]");
  }
}
