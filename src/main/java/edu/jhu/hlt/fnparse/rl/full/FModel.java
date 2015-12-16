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
import edu.jhu.hlt.fnparse.rl.full2.AbstractTransitionScheme;
import edu.jhu.hlt.fnparse.rl.full2.FNParseTransitionScheme;
import edu.jhu.hlt.fnparse.rl.full2.State2;
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

  // TODO Switch over to state2!
  private FNParseTransitionScheme ts;
  boolean useNewTS = true;

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
    conf.weights = new GeneralizedWeights(conf, null, l2UpdateInterval);
    rtConf = config;
    drp = new DeterministicRolePruning(pruningMode, null, null);
    timer = new MultiTimer.ShowPeriodically(15);

    if (useNewTS) {
      Primes primes = new Primes(ExperimentProperties.getInstance());
      CachedFeatures params = null;
      ts = new FNParseTransitionScheme(params, primes);
    }
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
    if (useNewTS)
      ts.setCachedFeatures(cf);
  }

  private Pair<Info, Info> getOracleAndMvInfo(FNParse y) {
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

    return new Pair<>(oracleInf, mvInf);
  }

  public Update getUpdate(FNParse y) {
    if (useNewTS)
      return getUpdateNew(y);
    else
      return getUpdateOld(y);
  }

  public Update getUpdateNew(FNParse y) {
    Pair<Info, Info> ormv = getOracleAndMvInfo(y);
    Info oracleInf = ormv.get1();
    Info mvInf = ormv.get2();

    timer.start("update.oracle");
    Pair<State2<Info>, DoubleBeam<State2<Info>>> oracleS =
        ts.runInference(ts.genRootState(oracleInf));
    timer.stop("update.oracle");

    timer.start("update.mv");
    Pair<State2<Info>, DoubleBeam<State2<Info>>> mvS =
        ts.runInference(ts.genRootState(mvInf));
    timer.stop("update.mv");

    StepScores<Info> oracleSc = oracleS.get2().pop().getStepScores();
    StepScores<Info> mvSc = mvS.get2().pop().getStepScores();

    return buildUpdate(oracleSc, mvSc);
  }

  public Update getUpdateOld(FNParse y) {
    Pair<Info, Info> ormv = getOracleAndMvInfo(y);
    Info oracleInf = ormv.get1();
    Info mvInf = ormv.get2();

    timer.start("update.oracle");
    Pair<State, DoubleBeam<State>> oracleStateColl = State.runInference(oracleInf);
    State oracleState = oracleStateColl.get2().pop(); // TODO consider items down the PQ
    timer.stop("update.oracle");

    timer.start("update.mv");
    Pair<State, DoubleBeam<State>> mvStateColl = State.runInference(mvInf);
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
    StepScores<Info> mvss = mvState.score;
    StepScores<Info> oss = oracleState.getStepScores();
    return buildUpdate(oss, mvss);
  }

  private Update buildUpdate(StepScores<Info> oss, StepScores<Info> mvss) {
//    final double hinge = Math.max(0,
//        mvss.getModelScore() + mvss.getHammingLoss()
//      - (oss.getModelScore() + oss.getHammingLoss()));
    final double hinge = Math.max(0,
        oss.constraintObjectivePlusConstant() - mvss.constraintObjectivePlusConstant());
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
//          oss.backwards(-learningRate);
//          mvss.backwards(+learningRate);
          oss.backwards(learningRate);
          mvss.backwards(learningRate);
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

  private Info getPredictInfo(FNParse y) {
    Info decInf = new Info(conf).setLike(rtConf).setDecodeCoefs();
    decInf.setLabel(y);
    decInf.setTargetPruningToGoldLabels();
    boolean includeGoldSpansIfMissing = true;
    decInf.setArgPruningUsingSyntax(drp, includeGoldSpansIfMissing);
    return decInf;
  }

  public FNParse predict(FNParse y) {
    if (useNewTS)
      return predictNew(y);
    else
      return predictOld(y);
  }

  public FNParse predictNew(FNParse y) {
    Info inf = getPredictInfo(y);
    State2<Info> s0 = ts.genRootState(inf);
    Pair<State2<Info>, DoubleBeam<State2<Info>>> i = ts.runInference(s0);
    State2<Info> beamLast = i.get1();
    return ts.decode(beamLast.getNode());
  }

  public FNParse predictOld(FNParse y) {
    timer.start("predict");
    Info decInf = getPredictInfo(y);
    FNParse yhat = State.runInference2(decInf);
    timer.stop("predict");
    return yhat;
  }
}
