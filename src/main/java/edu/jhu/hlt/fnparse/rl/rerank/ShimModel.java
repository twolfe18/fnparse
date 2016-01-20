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
import edu.jhu.hlt.fnparse.features.precompute.FeatureSet;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.full.Config;
import edu.jhu.hlt.fnparse.rl.full.FModel;
import edu.jhu.hlt.fnparse.rl.full.FModel.SimpleCFLike;
import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.fnparse.rl.full2.FNParseTransitionScheme;
import edu.jhu.hlt.fnparse.rl.full2.TFKS;
import edu.jhu.hlt.fnparse.rl.params.DecoderBias;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer.RTConfig;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
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
    if (fmodel != null) {
      // Maybe average each shard's perceptron weights
      int interval = ExperimentProperties.getInstance()
          .getInt("distributedPerceptron.combineEvery", 1000);
      if (iter > 0 && iter % interval == 0) {
        boolean redistribute = true;
        fmodel.combineWeightShards(redistribute);
      }
    }
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
      FNParseTransitionScheme ts = fmodel.getAverageWeights();
      return fmodel.predict(y, ts);
    }
  }

  public void doneTraining() {
    if (reranker != null) {
      if (verbose)
        Log.info("[main] telling reranker params that we're done training");
      reranker.getStatelessParams().doneTraining();
      reranker.getStatefulParams().doneTraining();
      reranker.getPruningParams().doneTraining();
    } else {
      if (verbose)
        Log.info("[main] setting fmodel params to average");
      boolean redistribute = false;
      fmodel.combineWeightShards(redistribute);
      fmodel.getAverageWeights().setParamsToAverage();
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
    if (reranker != null) {
      return getRerankerUpdate(batch, ip, es, verbose);
    } else {
      return getFModelUpdate(batch, ip, es, verbose);
    }
  }

  /** Return a single update which also does the combine operation */
  public List<Update> getFModelUpdate(List<Integer> batch, ItemProvider ip, ExecutorService es, boolean verbose) {

    int n = batch.size();
    if (n != fmodel.getNumShards()) {
      throw new IllegalStateException("you must match batch size with fmodel num shards:"
          + " batchSize=" + n
          + " numShards=" + fmodel.getNumShards());
    }
    List<Future<Update>> fus = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      FNParseTransitionScheme ts = fmodel.getShardWeights(new Shard(i, n));
      FNParse y = ip.label(batch.get(i));
      Future<Update> fu = es.submit(() -> {
        return fmodel.getUpdate(y, ts);
      });
      fus.add(fu);
    }

    List<Update> us = new ArrayList<>();
    for (Future<Update> fu : fus) {
      try {
        us.add(fu.get());
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return us;
  }

  public List<Update> getRerankerUpdate(List<Integer> batch, ItemProvider ip, ExecutorService es, boolean verbose) {
    List<Update> finishedUpdates = new ArrayList<>(batch.size());
    if (es == null) {
      for (int idx : batch) {
        FNParse y = ip.label(idx);
        finishedUpdates.add(getRerankerUpdate(y));
      }
    } else {
      List<Future<Update>> futures = new ArrayList<>(batch.size());
      for (int idx : batch) {
        futures.add(es.submit( () -> {
          FNParse y = ip.label(idx);
          return getRerankerUpdate(y);
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

  public Update getRerankerUpdate(FNParse y) {
    assert reranker != null;
    if (verbose)
      Log.info("getting update for " + y.getId() + " with reranker");
    State init = reranker.getInitialStateWithPruning(y, y);
    return reranker.hasStatefulFeatures() || conf.forceGlobalTrain
        ? reranker.getFullUpdate(init, y, conf.oracleMode, conf.rand, null, null)
            : reranker.getStatelessUpdate(init, y);
  }

  // NOTE: There is no getUpdate(FNParse) since for fmodel it is abiguous what
  // weights this update should be with respect to.

  public double getViolation(FNParse y) {
    if (reranker != null) {
      return getRerankerUpdate(y).violation();
    } else {
      FNParseTransitionScheme ts = fmodel.getAverageWeights();
      return fmodel.getUpdate(y, ts).violation();
    }
  }

  /**
   * This is only for main, RerankerTrainer implements distributed perceptron
   * a little differently: instead of doing one epoch (via one shard per thread)
   * and then combine, every batch must be size numThreads and then a combine
   * operation is done every once in a while via calledEveryIter.
   */
  private void runPerceptronOneShard(ExecutorService es, FModel m, Shard s, List<CachedFeatures.Item> ys) {
    es.submit(() -> {
      FNParseTransitionScheme ts = m.getShardWeights(s);
      List<CachedFeatures.Item> rel = ShardUtils.shardByIndex(ys, s);
      Log.info("shard=" + s + " n=" + rel.size());
      for (CachedFeatures.Item y : rel)
        m.getUpdate(y.getParse(), ts).apply(1);
    });
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws InterruptedException {
    ExperimentProperties config = ExperimentProperties.init(args);
    config.putIfAbsent("oracleMode", "MIN");
    config.putIfAbsent("beamSize", "1");
    config.putIfAbsent("oneAtATime", "" + TFKS.F);
    config.putIfAbsent("sortEggsMode", "BY_MODEL_SCORE");
    config.putIfAbsent("sortEggsKmaxS", "true");
    config.putIfAbsent("hashingTrickDim", "" + (1<<24));
//    config.putIfAbsent("threads", "1");
    int threads = config.getInt("threads", 1);
    int shards = config.getInt("shards", threads);

    FModel m = FModel.getFModel(config);
    Random rand = m.getConfig().rand;

    List<CachedFeatures.Item> train, dev, test;
    // Try to get data from memo
    File trainF = config.getFile("trainData");
    File devF = config.getFile("devData");
    File testF = config.getFile("testData");
    Log.info("[main] trainF=" + trainF.getPath()
        + " devF=" + devF.getPath()
        + " testF=" + testF.getPath());
    assert trainF.isFile() == devF.isFile() && devF.isFile() == testF.isFile();
    if (trainF.isFile()) {
      Log.info("[main] loading data from disk");
      train = (List<CachedFeatures.Item>) FileUtil.deserialize(trainF);
      dev = (List<CachedFeatures.Item>) FileUtil.deserialize(devF);
      test = (List<CachedFeatures.Item>) FileUtil.deserialize(testF);
    } else {
      Log.info("[main] generating data from scratch");

      // Load the feature set
      // NOTE: This must match the cached data files!
      File dd = new File("data/debugging/");
      File bf = config.getExistingFile("bialph", new File(dd, "coherent-shards-filtered-small/alphabet.txt"));
      BiAlph bialph = new BiAlph(bf, LineMode.ALPH);
      File fsParent = config.getFile("featureSetParent", dd);
      int fsC = config.getInt("fsC", 8);
      int fsN = config.getInt("fsN", 1280);
      File featureSetFile = config.getExistingFile("featureSet", new File(fsParent, "propbank-" + fsC + "-" + fsN + ".fs"));
      int[] template2cardinality = bialph.makeTemplate2Cardinality();
      int[][] featureSet = FeatureSet.getFeatureSet2(featureSetFile, bialph);

      // Load features
      File featuresParent = config.getExistingDir("featuresParent");
      String featuresGlob = config.getString("featuresGlob", "glob:**/shard*.txt*");
      List<CachedFeatures.Item>[] trainDevTest = FModel.foo2(
          featuresParent, featuresGlob, featureSet, template2cardinality);
      train = trainDevTest[0]; 
      dev = trainDevTest[1]; 
      test = trainDevTest[2]; 

      // Shuffle so that prefixes are random samples
      Random r = new Random(9001);
      Collections.shuffle(train, r);
      Collections.shuffle(dev, r);
      Collections.shuffle(test, r);

      Log.info("[main] saving data");
      FileUtil.serialize(train, trainF);  //new File("/tmp/shimmodel-propbank-train.jser.gz"));
      FileUtil.serialize(dev, devF);  //new File("/tmp/shimmodel-propbank-dev.jser.gz"));
      FileUtil.serialize(test, testF);  //new File("/tmp/shimmodel-propbank-test.jser.gz"));
    }
    Log.info("[main] done reading data");

    if (config.getBoolean("dontTrain", false)) {
      Log.info("[main] exiting because the dontTrain flag was given");
      return;
    }

    int ntl = config.getInt("nTrain", 0);
    if (ntl > 0 && ntl < train.size()) {
      Log.info("[main] limiting train set size to ntl=" + ntl);
      train = train.subList(0, ntl);
    }
    double expectedEpochs = config.getDouble("expectedEpochs", 2.5);
    config.putIfAbsent("kPerceptronAvg", "" + (expectedEpochs * train.size()));

    // This indexes features and computes: templates -> featureSet -> features
    SimpleCFLike cfLike = new SimpleCFLike();
    cfLike.addItems(train);
    cfLike.addItems(dev);
    cfLike.addItems(test);
//    cfLike.setFeatureset(featureSetFile, bialph);
    m.setCachedFeatures(cfLike);
    ShimModel sm = new ShimModel(m);

    // If true, on every combine operation, the average is sent to each shard.
    // This has the effect of letting each shard see all of the data. If you want
    // to compare against the data sub-sampling approach, each shard should only
    // see its data, and never an average, which is what happens if you set this
    // to false.
    boolean redistribute = config.getBoolean("redistribute", true);
    Log.info("starting"
        + " nTrain=" + train.size()
        + " nDev=" + dev.size()
        + " nTest=" + test.size()
        + " shards=" + shards
        + " threads=" + threads
        + " redistribute=" + redistribute);
    assert !FModel.overlappingIds2(train, test);
    assert !FModel.overlappingIds2(train, dev);
    assert !FModel.overlappingIds2(test, dev);

    // After this many epochs, spend time to compute the full average
    int noApproxAfter = config.getInt("noApproxAfterEpoch", 10);
    int numInstApprox = config.getInt("numInstApprox", 100);
    int maxEpoch = config.getInt("maxEpoch", 20);
    double alphaCum = 0;
    for (int epoch = 0; epoch < maxEpoch; epoch++) {
      double alpha = m.getAverageWeights().wHatch.getAlpha();
      alphaCum += alpha;
      Log.info("starting epoch=" + epoch
          + " alpha=" + alpha
          + " alphaCum=" + alphaCum);

      // Run perceptron on each shard separately
      Collections.shuffle(train, rand);
      ExecutorService es = Executors.newWorkStealingPool(threads);
      for (int i = 0; i < shards; i++) {
        Shard s = new Shard(i, shards);
        sm.runPerceptronOneShard(es, m, s, train);
      }
      es.shutdown();
      es.awaitTermination(999, TimeUnit.DAYS);

      // Measure performance
      // TEST
      int nTestLim = epoch >= noApproxAfter ? test.size() : Math.min(test.size(), numInstApprox);
      if (shards > 1)
        FModel.showLoss2(test.subList(0, nTestLim), m, m.getAverageWeights(), "TEST-avg-" + epoch);
      FModel.showLoss2(test.subList(0, nTestLim), m, m.getShardWeights(new Shard(0, shards)), "TEST-itr-" + epoch);
      // DEV
      int nDevLim = epoch >= noApproxAfter ? dev.size() : Math.min(dev.size(), numInstApprox);
      if (shards > 1)
        FModel.showLoss2(dev.subList(0, nDevLim), m, m.getAverageWeights(), "DEV-avg-" + epoch);
      FModel.showLoss2(dev.subList(0, nDevLim), m, m.getShardWeights(new Shard(0, shards)), "DEV-itr-" + epoch);
      // TRAIN
      int nTrainLim = Math.min(train.size(), numInstApprox);
      if (shards > 1)
        FModel.showLoss2(train.subList(0, nTrainLim), m, m.getAverageWeights(), "TRAIN-avg-" + epoch);
      FModel.showLoss2(train.subList(0, nTrainLim), m, m.getShardWeights(new Shard(0, shards)), "TRAIN-itr-" + epoch);

      // Compute the average and re-distribute
//      testAverage(m);
      m.combineWeightShards(redistribute);
    }
    m.setAllWeightsToAverage();
    if (shards > 1) {
      FModel.showLoss2(test, m, m.getAverageWeights(), "TEST-avg-final");
      FModel.showLoss2(dev, m, m.getAverageWeights(), "DEV-avg-final");
      FModel.showLoss2(train, m, m.getAverageWeights(), "TRAIN-avg-final");
    }
    FModel.showLoss2(test, m, m.getShardWeights(new Shard(0, shards)), "TEST-itr-final");
    FModel.showLoss2(dev, m, m.getShardWeights(new Shard(0, shards)), "DEV-itr-final");
    FModel.showLoss2(train, m, m.getShardWeights(new Shard(0, shards)), "TRAIN-itr-final");
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
