package edu.jhu.hlt.fnparse.rl.full;

import java.io.Serializable;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full.State.GeneralizedWeights;
import edu.jhu.hlt.fnparse.rl.full.State.Info;
import edu.jhu.hlt.fnparse.rl.full.State.StepScores;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer.RTConfig;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.prim.tuple.Pair;

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
    int l2UpdateInterval = 32;
    // Params is set later in setCachedFeatures
//    conf.weights = new GeneralizedWeights(conf, new CachedFeatureParamsShim() {
//      @Override
//      public IntDoubleUnsortedVector getFeatures(Sentence s, Span t, Span a) {
//        if (cachedFeatures == null)
//          throw new RuntimeException("cachedFeatures was never set!");
//        if (cachedFeatures.params == null)
//          throw new RuntimeException("CachedFeatures.Params was never instantiated?");
//        return cachedFeatures.params.getFeatures(s, t, a);
//      }
//    }, l2UpdateInterval);
    conf.weights = new GeneralizedWeights(conf, null, l2UpdateInterval);
    rtConf = config;
    drp = new DeterministicRolePruning(pruningMode, null, null);
    timer = new MultiTimer.ShowPeriodically(15);
  }

  public void setConfig(Config c) {
    this.conf = c;
  }

  public Config getConfig() {
    return conf;
  }

  // Needed unless you setup syntactic parsers
  public void setCachedFeatures(CachedFeatures cf) {
    cachedFeatures = cf;
    drp.cachedFeatures = cf;
    conf.weights.setStaticFeatures(cf.params);
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

    timer.start("update.oracle");
    Pair<State, DoubleBeam> oracleStateColl = State.runInference(oracleInf);
    State oracleState = oracleStateColl.get2().pop(); // TODO consider items down the PQ
    timer.stop("update.oracle");

    timer.start("update.mv");
    Pair<State, DoubleBeam> mvStateColl = State.runInference(mvInf);
    State mvState = mvStateColl.get2().pop(); // TODO consider items down the PQ
    timer.stop("update.mv");

    // Pull loss out of state/trajectory
//    double orL = oracleState.score.getHammingLoss();
//    double mvL = mvState.score.getHammingLoss();
//    double margin = mvL - orL;
//    if (margin < 0)
//      Log.warn("oracleLoss=" + orL + " > mvLoss=" + mvL);

    // mv.forwards() contains loss, oracle doesn't due to muting
//    final double hinge = Math.max(0, mvState.score.forwards() + margin - oracleState.score.forwards());
    // Shit! oracle.forwards = loss(y,y_oracle) -- which should be basically 0
    // It should be score(y_oracle)!
//    final double hinge = Math.max(0, mvState.score.forwards() - oracleState.score.forwards());
    StepScores mvss = mvState.score;
    StepScores oss = oracleState.score;
    final double hinge = Math.max(0,
        mvss.getModelScore() + mvss.getHammingLoss()
      - (oss.getModelScore() + oss.getHammingLoss()));
    Log.info("mv.score=" + mvss.getModelScore()
        + " mv.loss=" + mvss.getModelScore()
        + " oracle.score=" + oss.getModelScore()
        + " oracle.loss=" + oss.getHammingLoss()
        + " hinge=" + hinge);
    assert mvss.getHammingLoss() >= 0 : "mvss=" + mvss;
    assert oss.getHammingLoss() >= 0 : "oss=" + oss;

    return new Update() {
      @Override
      public double apply(double learningRate) {
        if (hinge > 0) {
          timer.start("update.apply");
          // NOTE: The reason that I've switched to same sign in back-prop
          // is primarily StepScore. That is oracle has a *muteForwards* model
          // coef of 1 and MV has an *unmuted* model coef of -1, leading to the
          // += behavior for oracle and -= for MV.
//          oracleState.score.backwards(-learningRate);
//          mvState.score.backwards(+learningRate);
          oracleState.score.backwards(learningRate);
          mvState.score.backwards(learningRate);
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
