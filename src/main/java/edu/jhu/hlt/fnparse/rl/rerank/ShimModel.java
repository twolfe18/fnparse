package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.full.Config;
import edu.jhu.hlt.fnparse.rl.full.FModel;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer.RTConfig;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Timer;

/**
 * This is a shim designed to replace {@link Reranker}.
 */
public class ShimModel {

  private final Reranker reranker;
  private final RTConfig conf;

  private final FModel fmodel;

  private CachedFeatures cachedFeatures;

  public ShimModel(Reranker r, RTConfig conf) {
    reranker = r;
    this.conf = conf;
    fmodel = null;
  }

  public ShimModel(FModel m) {
    reranker = null;
    conf = null;
    fmodel = m;
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

  public void observeConfiguration(ExperimentProperties config) {
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
    Log.info("setting CachedFeatures");
    cachedFeatures = cf;
    if (fmodel != null)
      fmodel.setCachedFeatures(cf);
  }

  public CachedFeatures getCachedFeatures() {
    return cachedFeatures;
  }

  public FNParse predict(FNParse y) {
    if (reranker != null) {
      State init = reranker.getInitialStateWithPruning(y, y);
      FNParse yhat = reranker.predict(init);
      return yhat;
    } else {
      return fmodel.predict(y);
    }
  }

  public void doneTraining() {
    if (reranker != null) {
      reranker.getStatelessParams().doneTraining();
      reranker.getStatefulParams().doneTraining();
      reranker.getPruningParams().doneTraining();
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

  private List<Update> getUpdateReranker(List<Integer> batch, ItemProvider ip, ExecutorService es, boolean verbose) throws InterruptedException, ExecutionException {
    List<Update> finishedUpdates = new ArrayList<>();
    Reranker r = reranker;
    Timer tmv = null; // TODO
    Timer to = null;  // TODO
    if (es == null) {
      if (verbose)
        Log.info("[hammingTrainBatch] running serial");
      for (int idx : batch) {
        if (verbose)
          Log.info("[hammingTrainBatch] submitting " + idx);
        FNParse y = ip.label(idx);
        State init = r.getInitialStateWithPruning(y, y);
        Update u = r.hasStatefulFeatures() || conf.forceGlobalTrain
            ? r.getFullUpdate(init, y, conf.oracleMode, conf.rand, to, tmv)
                : r.getStatelessUpdate(init, y);
        finishedUpdates.add(u);
      }
    } else {
      List<Future<Update>> futures = new ArrayList<>(batch.size());
      for (int idx : batch) {
        futures.add(es.submit( () -> {
          FNParse y = ip.label(idx);
          State init = r.getInitialStateWithPruning(y, y);
          return r.hasStatefulFeatures() || conf.forceGlobalTrain
              ? r.getFullUpdate(init, y, conf.oracleMode, conf.rand, to, tmv)
                  : r.getStatelessUpdate(init, y);
        } ));
      }
      for (Future<Update> f : futures)
        finishedUpdates.add(f.get());
    }
    return finishedUpdates;
  }

  private List<Update> getUpdateFModel(List<Integer> batch, ItemProvider ip, ExecutorService es, boolean verbose) throws InterruptedException, ExecutionException {
    List<Update> finishedUpdates = new ArrayList<>(batch.size());
    if (es == null) {
      for (int idx : batch) {
        FNParse y = ip.label(idx);
        finishedUpdates.add(fmodel.getUpdate(y));
      }
    } else {
      List<Future<Update>> futures = new ArrayList<>(batch.size());
      for (int idx : batch) {
        futures.add(es.submit( () -> {
          FNParse y = ip.label(idx);
          return fmodel.getUpdate(y);
        } ));
      }
      for (Future<Update> f : futures)
        finishedUpdates.add(f.get());
    }
    return finishedUpdates;
  }

  public List<Update> getUpdate(List<Integer> batch, ItemProvider ip, ExecutorService es, boolean verbose) {
    try {
      if (reranker != null) {
        return getUpdateReranker(batch, ip, es, verbose);
      } else {
        return getUpdateFModel(batch, ip, es, verbose);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public Update getUpdate(FNParse y) {
    if (reranker != null) {
      State init = reranker.getInitialStateWithPruning(y, y);
      return reranker.hasStatefulFeatures() || conf.forceGlobalTrain
          ? reranker.getFullUpdate(init, y, conf.oracleMode, conf.rand, null, null)
              : reranker.getStatelessUpdate(init, y);
    } else {
      return fmodel.getUpdate(y);
    }
  }
}