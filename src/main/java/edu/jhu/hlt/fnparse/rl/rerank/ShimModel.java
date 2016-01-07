package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.full.Config;
import edu.jhu.hlt.fnparse.rl.full.FModel;
import edu.jhu.hlt.fnparse.rl.params.DecoderBias;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer.RTConfig;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;

/**
 * This is a shim designed to replace {@link Reranker}.
 */
public class ShimModel {

  private boolean verbose;

  private final Reranker reranker;
  private final RTConfig conf;

  private final FModel fmodel;
  private int itersBetweenPerceptronWeightAverages =
      ExperimentProperties.getInstance()
        .getInt("itersBetweenPerceptronWeightAverages", 500);

  private CachedFeatures cachedFeatures;

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

  private TimeMarker showWeights = new TimeMarker();
  public void callEveryIter(int iter) {
    if (fmodel != null) {
      if (iter % itersBetweenPerceptronWeightAverages == 0)
        fmodel.getTransitionSystem().takeAverageOfWeights();
      if (showWeights.enoughTimePassed(60))
        fmodel.getTransitionSystem().showWeights();
    } else {
      // no-op
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
      return fmodel.predict(y);
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
      fmodel.getTransitionSystem().setParamsToAverage();
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
      fmodel.getTransitionSystem().showWeights();
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
      return fmodel.getUpdate(y);
    }
  }
}
