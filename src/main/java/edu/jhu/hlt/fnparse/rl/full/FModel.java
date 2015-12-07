package edu.jhu.hlt.fnparse.rl.full;

import java.io.Serializable;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.rl.full.State.CachedFeatureParamsShim;
import edu.jhu.hlt.fnparse.rl.full.State.GeneralizedWeights;
import edu.jhu.hlt.fnparse.rl.full.State.Info;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer.RTConfig;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;

/**
 * Don't ask me why its called FModel. This is is a wrapper around {@link State}.
 *
 * NOTE: NOT THREAD SAFE!
 *
 * @author travis
 */
public class FModel implements Serializable {
  private static final long serialVersionUID = -3155569129086851946L;

  private Config conf;
  private RTConfig rtConf;
  private DeterministicRolePruning drp;
  private MultiTimer.ShowPeriodically timer;
  private CachedFeatures cachedFeatures;

  public FModel(
      RTConfig config,
      DeterministicRolePruning.Mode pruningMode) {

    ExperimentProperties p = ExperimentProperties.getInstance();
    FrameIndex fi;
    if (p.getBoolean("propbank")) {
      fi = FrameIndex.getPropbank();
    } else {
      fi = FrameIndex.getFrameNet();
    }

    conf = new Config();
    conf.frPacking = new FrameRolePacking(fi);
    conf.primes = new PrimesAdapter(new Primes(p), conf.frPacking);
    conf.weights = new GeneralizedWeights(conf, new CachedFeatureParamsShim() {
      @Override
      public IntDoubleUnsortedVector getFeatures(Sentence s, Span t, Span a) {
        if (cachedFeatures == null)
          throw new RuntimeException("cachedFeatures was never set!");
        if (cachedFeatures.params == null)
          throw new RuntimeException("CachedFeatures.Params was never instantiated?");
        return cachedFeatures.params.getFeatures(s, t, a);
      }
    });
    rtConf = config;
    drp = new DeterministicRolePruning(pruningMode, null, null);
    timer = new MultiTimer.ShowPeriodically(15);
  }

  public Config getConfig() {
    return conf;
  }

  // Needed unless you setup syntactic parsers
  public void setCachedFeatures(CachedFeatures cf) {
    cachedFeatures = cf;
    drp.cachedFeatures = cf;
  }

  public Update getUpdate(FNParse y) {

    timer.start("update.setup.other");
    Info oracleInf = new Info(conf).setLike(rtConf).setOracleCoefs();
    Info mvInf = new Info(conf).setLike(rtConf).setMostViolatedCoefs();
    oracleInf.setLabel(y);
    mvInf.copyLabel(oracleInf);
    oracleInf.setTargetPruningToGoldLabels(mvInf);
    timer.stop("update.setup.other");

    timer.start("update.setup.argPrune");
    boolean includeGoldSpansIfMissing = true;
    oracleInf.setArgPruningUsingSyntax(drp, includeGoldSpansIfMissing, mvInf);
    timer.stop("update.setup.argPrune");

    // TODO Compute loss
    final double loss = 1;

    timer.start("update.oracle");
    final State oracleState = State.runInference(oracleInf);
    timer.stop("update.oracle");

    timer.start("update.mv");
    final State mvState = State.runInference(mvInf);
    timer.stop("update.mv");

    final double hinge = Math.max(0, mvState.score.forwards() + loss - oracleState.score.forwards());
    return new Update() {
      @Override
      public double apply(double learningRate) {
        if (hinge > 0) {
          timer.start("update.apply");
          oracleState.score.backwards(-learningRate);
          mvState.score.backwards(+learningRate);
          timer.stop("update.apply");
        }
        return hinge;
      }
      @Override
      public double violation() {
        return hinge;
      }
    };
  }

  public FNParse predict(FNParse y) {
    timer.start("predict");
    Info decInf = new Info(conf).setLike(rtConf).setDecodeCoefs();
    decInf.setLabel(y);
    decInf.setTargetPruningToGoldLabels();
    boolean includeGoldSpansIfMissing = true;
    decInf.setArgPruningUsingSyntax(drp, includeGoldSpansIfMissing);
    FNParse yhat = State.runInference2(decInf);
    timer.stop("predict");
    return yhat;
  }
}
