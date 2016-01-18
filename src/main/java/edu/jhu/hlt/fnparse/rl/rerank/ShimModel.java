package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.full.Config;
import edu.jhu.hlt.fnparse.rl.full.FModel;
import edu.jhu.hlt.fnparse.rl.full.FModel.SimpleCFLike;
import edu.jhu.hlt.fnparse.rl.full2.FNParseTransitionScheme;
import edu.jhu.hlt.fnparse.rl.full2.TFKS;
import edu.jhu.hlt.fnparse.rl.params.DecoderBias;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer.RTConfig;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ShardUtils;
import edu.jhu.hlt.tutils.ShardUtils.Shard;

/**
 * This is a shim designed to replace {@link Reranker}.
 */
public class ShimModel implements Serializable {
  private static final long serialVersionUID = 1841248078348564919L;

  private boolean verbose;

  private final Reranker reranker;
  private final RTConfig conf;

  private final FModel fmodel;

  private transient CachedFeatures cachedFeatures;

  public ShimModel(Reranker r, RTConfig conf) {
    Log.info("[main] starting with Reranker");
    reranker = r;
    this.conf = conf;
    fmodel = null;
    verbose = ExperimentProperties.getInstance().getBoolean("verbose.ShimModel", false);
  }

  public ShimModel(FModel m) {
    Log.info("[main] starting with FModel");
    reranker = null;
    conf = null;
    fmodel = m;
    verbose = ExperimentProperties.getInstance().getBoolean("verbose.ShimModel", false);
  }

  public void callEveryIter(int iter) {
    // no-op
  }

  public boolean isFModel() {
    return fmodel != null;
  }
  public boolean isRerankerModel() {
    return reranker != null;
  }

  public Reranker getReranker() {
    if (reranker == null)
      throw new RuntimeException("no reranker here!");
    return reranker;
  }

  public FModel getFModel() {
    if (fmodel == null)
      throw new RuntimeException("no fmodel here!");
    return fmodel;
  }

  /**
   * Returns a function which allows you to set a bias feature for all
   * prune features.
   */
  public Consumer<Double> getPruningBias() {
    if (verbose)
      Log.info("getting pruning bias");
    if (reranker != null) {
      Params.PruneThreshold tau = reranker.getPruningParams();
      DecoderBias bias = new DecoderBias();
      reranker.setPruningParams(new Params.PruneThreshold.Sum(bias, tau));
      return bias::setRecallBias;
    } else {
      return d -> {
        Log.info("[main] setting fmodel recal bias: "
            + fmodel.getConfig().recallBias
            + " => " + d);
        fmodel.getConfig().recallBias = d;
      };
    }
  }

  public void observeConfiguration(ExperimentProperties config) {
    if (verbose)
      Log.info("[main] isFModel=" + isFModel());
    if (fmodel != null) {
      // NOTE: This code path is deprecated, argLoc, numArgs, and roleCooc
      // are read directly from LLSSPatF at class initialization.
      Config c = fmodel.getConfig();
      c.argLocFeature = config.getBoolean("globalFeatArgLocSimple", false);
      c.numArgsFeature = config.getBoolean("globalFeatNumArgs", false);
      c.roleCoocFeature = config.getBoolean("globalFeatRoleCoocSimple", false);
      Log.info("[main] argLoc=" + c.argLocFeature + " numArgs=" + c.numArgsFeature + " roleCooc=" + c.roleCoocFeature);
    }
  }

  public void setCachedFeatures(CachedFeatures cf) {
    if (verbose)
      Log.info("[main] setting CachedFeatures");
    cachedFeatures = cf;
    if (fmodel != null)
      fmodel.setCachedFeatures(cf.params);
  }

  public CachedFeatures getCachedFeatures() {
    return cachedFeatures;
  }

  public FNParse predict(FNParse y) {
    if (reranker != null) {
      if (verbose)
        Log.info("predicting on " + y.getId() + " with reranker");
      State init = reranker.getInitialStateWithPruning(y, y);
      FNParse yhat = reranker.predict(init);
      return yhat;
    } else {
      if (verbose)
        Log.info("predicting on " + y.getId() + " with fmodel");
//      return fmodel.predict(y);
      throw new RuntimeException("implement me");
    }
  }

  public void doneTraining() {
    if (reranker != null) {
      if (verbose)
        Log.info("telling reranker params that we're done training");
      reranker.getStatelessParams().doneTraining();
      reranker.getStatefulParams().doneTraining();
      reranker.getPruningParams().doneTraining();
    } else {
      if (verbose)
        Log.info("setting fmodel params to average");

//      fmodel.getTransitionSystem().setParamsToAverage();
//      // For tuning P/R, we need to know the scores will be in some standard
//      // range, so we normalize the average (perceptron is invariant to scaling)
//      fmodel.getTransitionSystem().makeWeightUnitLength();

//      fmodel.updateAverageShardWeights();
      fmodel.combineWeightShards();
      fmodel.getAverageWeights().makeWeightUnitLength();
    }
  }

  public void showParams() {
    if (reranker != null) {
      Log.info("model stateful params:");
      reranker.getStatefulParams().showWeights();
      Log.info("model stateless params:");
      reranker.getStatelessParams().showWeights();
      Log.info("model tau params:");
      reranker.getPruningParams().showWeights();
    } else {
      fmodel.getAverageWeights().showWeights();
    }
  }

  public void deserializeParams(DataInputStream dis) throws IOException {
    if (reranker != null)
      reranker.deserializeParams(dis);
    else
      throw new RuntimeException("implement me");
  }

  public void serializeParams(DataOutputStream dos) throws IOException {
    if (reranker != null)
      reranker.serializeParams(dos);
    else
      throw new RuntimeException("implement me");
  }

  public List<Update> getUpdate(List<Integer> batch, ItemProvider ip, ExecutorService es, boolean verbose) {
    List<Update> finishedUpdates = new ArrayList<>(batch.size());
    if (es == null) {
      for (int idx : batch) {
        FNParse y = ip.label(idx);
        finishedUpdates.add(getUpdate(y));
      }
    } else {
      List<Future<Update>> futures = new ArrayList<>(batch.size());
      for (int idx : batch) {
        futures.add(es.submit( () -> {
          FNParse y = ip.label(idx);
          return getUpdate(y);
        } ));
      }
      try {
        for (Future<Update> f : futures)
          finishedUpdates.add(f.get());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return finishedUpdates;
  }

  public Update getUpdate(FNParse y) {
    if (reranker != null) {
      if (verbose)
        Log.info("getting update for " + y.getId() + " with reranker");
      State init = reranker.getInitialStateWithPruning(y, y);
      return reranker.hasStatefulFeatures() || conf.forceGlobalTrain
          ? reranker.getFullUpdate(init, y, conf.oracleMode, conf.rand, null, null)
              : reranker.getStatelessUpdate(init, y);
    } else {
      if (verbose)
        Log.info("getting update for " + y.getId() + " with fmodel");
//      return fmodel.getUpdate(y);
      throw new RuntimeException("implement me");
    }
  }

  private void runPerceptronOneShard(ExecutorService es, FModel m, Shard s, List<CachedFeatures.Item> ys) {
    es.submit(() -> {
      FNParseTransitionScheme ts = m.getShardWeights(s);
      List<CachedFeatures.Item> rel = ShardUtils.shardByIndex(ys, s);
      Log.info("shard=" + s + " n=" + rel.size());
      for (CachedFeatures.Item y : rel)
        m.getUpdate(y.getParse(), ts).apply(1);
    });
  }

  public static void main(String[] args) throws InterruptedException {
    ExperimentProperties config = ExperimentProperties.init(args);
    config.putIfAbsent("oracleMode", "RAND");
    config.putIfAbsent("beamSize", "1");
    config.putIfAbsent("oneAtATime", "" + TFKS.F);
    config.putIfAbsent("sortEggsMode", "BY_MODEL_SCORE");
    config.putIfAbsent("sortEggsKmaxS", "true");
    config.putIfAbsent("threads", "3");
    config.putIfAbsent("hashingTrickDim", "" + (1<<20));

    FModel m = FModel.getFModel(config);
    Random rand = m.getConfig().rand;

    List<CachedFeatures.Item> stuff = FModel.fooMemo();
//    stuff = stuff.subList(0, 150);

    SimpleCFLike cfLike = new SimpleCFLike(stuff);
    m.setCachedFeatures(cfLike);

    // Load the feature set
    File dd = new File("data/debugging/");
    File bf = config.getExistingFile("bialph", new File(dd, "coherent-shards-filtered-small/alphabet.txt"));
    BiAlph bialph = new BiAlph(bf, LineMode.ALPH);
    File fsParent = config.getFile("featureSetParent", dd);
    int fsC = config.getInt("fsC", 8);
    int fsN = config.getInt("fsN", 1280);
    File featureSetFile = config.getExistingFile("featureSet", new File(fsParent, "propbank-" + fsC + "-" + fsN + ".fs"));
    cfLike.setFeatureset(featureSetFile, bialph);

    ShimModel sm = new ShimModel(m);

    int threads = config.getInt("threads");
    int numFold = 10;
    for (int fold = 0; fold < numFold; fold++) {
      List<CachedFeatures.Item> train = new ArrayList<>();
      List<CachedFeatures.Item> test = new ArrayList<>();
      for (int i = 0; i < stuff.size(); i++)
        (i % numFold == fold ? test : train).add(stuff.get(i));
      Log.info("starting fold=" + fold
          + " nTrain=" + train.size()
          + " nTest=" + test.size());
      assert !FModel.overlappingIds2(train, test);

      boolean includeAverage = true;
      m.zeroWeights(includeAverage);

      for (int epoch = 0; epoch < 150; epoch++) {
        Log.info("starting epoch=" + epoch);
        // Split the data
        Collections.shuffle(train, rand);
        // We'll just take each thread to be on shard t%N

        // Run perceptron on each shard separately
        ExecutorService es = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
          Shard s = new Shard(t, threads);
          sm.runPerceptronOneShard(es, m, s, train);
        }
        es.shutdown();
        es.awaitTermination(1, TimeUnit.DAYS);

        // Compute the average and re-distribute
//        m.updateAverageShardWeights();
        m.combineWeightShards();

        // Measure test set performance
        FModel.showLoss2(test, m, m.getAverageWeights(), "TEST-avg-" + epoch);
        FModel.showLoss2(test, m, m.getShardWeights(new Shard(0, threads)), "TEST-itr-" + epoch);
        int n = Math.min(train.size(), 60);
        FModel.showLoss2(train.subList(0, n), m, m.getAverageWeights(), "TRAIN-avg-" + epoch);
        FModel.showLoss2(train.subList(0, n), m, m.getShardWeights(new Shard(0, threads)), "TRAIN-itr-" + epoch);
        testAverage(m);
      }
      // Before we were using the average of the shards current iterates.
      // This takes the average of those.
      // TODO This is not the same as averaging over all shard-iterates!
      m.getAverageWeights().setParamsToAverage();
      FModel.showLoss2(test, m, m.getAverageWeights(), "TEST-final");
    }
  }

  private static void testAverage(FModel m) {
    int D = ExperimentProperties.getInstance().getInt("hashingTrickDim");
    Random rand = new Random(9001);
    int numWeightsToShow = 10;
    for (int i = 0; i < numWeightsToShow; i++) {
      int d = rand.nextInt(D);
      double avg = m.getAverageWeights().wHatch.getWeight(d);
      Log.info("w[testW," + d + "]=" + avg);
      double avg2 = m.getAverageWeights().wHatch.getAveragedWeight(d);
      Log.info("w[testWavg," + d + "]=" + avg2);
      int S = m.getNumShards();
      for (int j = 0; j < S; j++) {
        Shard s = new Shard(j, S);
        double wj = m.getShardWeights(s).wHatch.getWeight(d);
        Log.info("w[trainW" + j + "," + d + "]=" + wj);
        double wj2 = m.getShardWeights(s).wHatch.getAveragedWeight(d);
        Log.info("w[trainWavg" + j + "," + d + "]=" + wj2);
      }
      System.out.println();
    }
  }
}
