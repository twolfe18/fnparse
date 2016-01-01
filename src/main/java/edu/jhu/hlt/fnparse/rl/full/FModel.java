package edu.jhu.hlt.fnparse.rl.full;

import java.io.File;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.LabelIndex;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full2.AbstractTransitionScheme;
import edu.jhu.hlt.fnparse.rl.full2.FNParseTransitionScheme;
import edu.jhu.hlt.fnparse.rl.full2.FNParseTransitionScheme.SortEggsMode;
import edu.jhu.hlt.fnparse.rl.full2.State2;
import edu.jhu.hlt.fnparse.rl.full2.TFKS;
import edu.jhu.hlt.fnparse.rl.full2.AbstractTransitionScheme.PerceptronUpdateMode;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer.OracleMode;
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

  public static boolean DEBUG_SEARCH_FINAL_SOLN = true;

  public static boolean HIJACK_GET_UPDATE_FOR_DEBUG = false;

  private Config conf;
  private RTConfig rtConf;
  private DeterministicRolePruning drp;
  private MultiTimer.ShowPeriodically timer;
//  private CachedFeatures cachedFeatures;
  private FNParseTransitionScheme ts;

  // LH's max violation
  private boolean maxViolation;
  public PerceptronUpdateMode perceptronUpdateMode;

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

    maxViolation = p.getBoolean("perceptron");
    perceptronUpdateMode =
        p.getBoolean("maxViolation", true)
        ? PerceptronUpdateMode.MAX_VIOLATION
            : PerceptronUpdateMode.EARLY;
    Log.info("[main] perceptron=" + maxViolation + " mode=" + perceptronUpdateMode);

    conf = new Config();
    conf.frPacking = new FrameRolePacking(fi);
    conf.primes = new PrimesAdapter(new Primes(p), conf.frPacking);

    // TODO Remove, this is deprecate (FNParseTS has weights now)
//    int l2UpdateInterval = 32;
//    int dimension = 1;  // Not using the weights in here, don't waste space
//    conf.weights = new GeneralizedWeights(conf, null, dimension, l2UpdateInterval);

    rtConf = config;
    drp = new DeterministicRolePruning(pruningMode, null, null);
    timer = new MultiTimer.ShowPeriodically(15);

    Primes primes = new Primes(ExperimentProperties.getInstance());
    CachedFeatures params = null;
    ts = new FNParseTransitionScheme(params, primes);
    if (maxViolation && ts.sortEggsMode != SortEggsMode.NONE)
      Log.warn("[main] perceptron=true and sorting eggs?! don't do this");
  }

  public FNParseTransitionScheme getTransitionSystem() {
    return ts;
  }

  public void setConfig(Config c) {
    this.conf = c;
  }

  public Config getConfig() {
    return conf;
  }

  // Needed unless you setup syntactic parsers
  public void setCachedFeatures(CachedFeatures cf) {
//    cachedFeatures = cf;
    drp.cachedFeatures = cf;
//    conf.weights.setStaticFeatures(cf.params);
    ts.setCachedFeatures(cf);
  }

  private Pair<Info, Info> getOracleAndMvInfo(FNParse y) {
    timer.start("update.setup.other");
    Info oracleInf = new Info(conf).setLike(rtConf).setOracleCoefs();
    Info mvInf = new Info(conf).setLike(rtConf).setMostViolatedCoefs();
    oracleInf.shareStaticFeatureCacheWith(mvInf);
    oracleInf.setLabel(y, ts);
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
    if (HIJACK_GET_UPDATE_FOR_DEBUG)
      hijack(y);

    return getUpdateNew(y);
  }

  private void hijack(FNParse y) {
    Log.info("presumably with CachedFeatures enabled, lets try to see if we can make an update work");

    AbstractTransitionScheme.DEBUG = true;
    FNParseTransitionScheme.DEBUG_FEATURES = true;
    Update u = getUpdateNew(y);
    u.apply(1);

    FNParse yhat = predict(y);
    SentenceEval se = new SentenceEval(y, yhat);
    Map<String, Double> r = BasicEvaluation.evaluate(Arrays.asList(se));
    double f1 = r.get("ArgumentMicroF1");
    Log.info("result: " + y.getSentence().getId() 
        + " f1=" + f1
        + " p=" + r.get("ArgumentMicroPRECISION")
        + " r=" + r.get("ArgumentMicroRECALL"));

    Log.info("done hijacking");
  }

  public Update getUpdateNew(FNParse y) {
    Pair<Info, Info> ormv = getOracleAndMvInfo(y);
    Info oracleInf = ormv.get1();
    Info mvInf = ormv.get2();

    if (maxViolation) {
      timer.start("update.perceptron");
      ts.flushPrimes();
      HowToSearch decoder = getPredictInfo(y).htsBeam;
      State2<Info> s0 = ts.genRootState(oracleInf);
      Update u = ts.perceptronUpdate(s0, decoder, perceptronUpdateMode);
      timer.stop("update.perceptron");
      return u;
    }

    timer.start("update.oracle");
    if (AbstractTransitionScheme.DEBUG)
      Log.info("doing oracle search...");
    ts.flushPrimes();
    Pair<State2<Info>, DoubleBeam<State2<Info>>> oracleS =
        ts.runInference(ts.genRootState(oracleInf), oracleInf);
    timer.stop("update.oracle");

    timer.start("update.mv");
    if (AbstractTransitionScheme.DEBUG)
      Log.info("doing most violated search...");
    ts.flushPrimes();
    Pair<State2<Info>, DoubleBeam<State2<Info>>> mvS =
        ts.runInference(ts.genRootState(mvInf), mvInf);
    timer.stop("update.mv");

    // Oracle gets the last state because that enforces the constraint that Proj(z) == {y}
    State2<?> oracleSt = oracleS.get1();
    StepScores<?> oracleSc = oracleSt.getStepScores();
    CoefsAndScoresAdjoints oracleB = new CoefsAndScoresAdjoints(oracleInf.htsConstraints, oracleSc);
    // MostViolated may take any prefix according to s(z) + max_{y \in Proj(z)} loss(y)
    State2<?> mvSt = mvS.get2().peek();
    StepScores<?> mvSc = mvSt.getStepScores();
    CoefsAndScoresAdjoints mvB = new CoefsAndScoresAdjoints(mvInf.htsConstraints, mvSc);

    if (AbstractTransitionScheme.DEBUG && DEBUG_SEARCH_FINAL_SOLN) {
      Log.info("oracle terminal state: (overfit=" + ts.useOverfitFeatures + ")");
      oracleSt.getRoot().show(System.out);
      Log.info("MV terminal state: (overfit=" + ts.useOverfitFeatures + ")");
      mvSt.getRoot().show(System.out);
    }

    if (oracleSc.getLoss().maxLoss() > 0) {
      if (ts.disallowNonLeafPruning && oracleSc.getLoss().fn == 0) {
        // TODO The cause of this is FPs counted on not pruning the non-leaf
        // nodes.
      } else {
        if (badThings++ % 30 == 0) {
          System.err.println("BAD ORACLE FINAL STATE:");
          oracleSt.getRoot().show(System.err);
        }
        System.err.println("oracle has loss: " + oracleSc.getLoss());
        System.err.println("mode="+ this.rtConf.oracleMode);
        System.err.println("mvInf=" + mvInf);
        System.err.println("oracleInf=" + oracleInf);
      }
//      Log.warn("check your pruning! if you pruned away a branch on the gold tree"
//            + " then you should consider reifying the pruning process into"
//            + " learning (since its so good at handling pruning!)\n"
//            + oracleSc.getLoss());
    }

    return buildUpdate(oracleB, mvB, false);
  }
  private int badThings = 0;

  public Update getUpdateOld(FNParse y) {
    Pair<Info, Info> ormv = getOracleAndMvInfo(y);
    Info oracleInf = ormv.get1();
    Info mvInf = ormv.get2();

    timer.start("update.oracle");
    Pair<State, DoubleBeam<State>> oracleStateColl = State.runInference(oracleInf);
//    State oracleState = oracleStateColl.get2().pop(); // TODO consider items down the PQ
    State oracleState = oracleStateColl.get1();   // get1 b/c need to enforce constraint that 
    assert oracleState.getStepScores().getLoss().maxLoss() == 0 : "for testing use an exact oracle";
    CoefsAndScoresAdjoints oracleBackwards = new CoefsAndScoresAdjoints(oracleInf.htsConstraints, oracleState.getStepScores());
    timer.stop("update.oracle");

    timer.start("update.mv");
    Pair<State, DoubleBeam<State>> mvStateColl = State.runInference(mvInf);
    State mvState = mvStateColl.get2().pop(); // TODO consider items down the PQ
    CoefsAndScoresAdjoints mvBackwards = new CoefsAndScoresAdjoints(mvInf.htsConstraints, mvState.getStepScores());
    timer.stop("update.mv");

    return buildUpdate(oracleBackwards, mvBackwards, false);
  }

  private Update buildUpdate(CoefsAndScoresAdjoints oracle, CoefsAndScoresAdjoints mv, boolean ignoreHinge) {

//    assert oracle.scores.getLoss().noLoss() : "oracle has loss: " + oracle.scores.getLoss();
    double a = mv.scores.getModel().forwards() + mv.scores.getLoss().maxLoss();
    double b = oracle.scores.getModel().forwards() + oracle.scores.getLoss().maxLoss();
    final double hinge = Math.max(0, a - b);

    if (AbstractTransitionScheme.DEBUG && (DEBUG_SEARCH_FINAL_SOLN || AbstractTransitionScheme.DEBUG_PERCEPTRON)) {
      Log.info("mv.model=" + mv.scores.getModel().forwards());
      Log.info("mv.loss=" + mv.scores.getLoss());
      Log.info("oracle.score=" + oracle.scores.getModel().forwards());
      Log.info("oracle.loss=" + oracle.scores.getLoss());
      Log.info("hinge=" + hinge + " = max(0, " + a + " - " + b + ")");
    }

    return new Update() {
      @Override
      public double apply(double learningRate) {
        if (hinge > 0 || ignoreHinge) {
          timer.start("update.apply");
          if (AbstractTransitionScheme.DEBUG && FNParseTransitionScheme.DEBUG_FEATURES)
            Log.info("about to apply the oracle updates");
          oracle.backwards(-learningRate);
          if (AbstractTransitionScheme.DEBUG && FNParseTransitionScheme.DEBUG_FEATURES)
            Log.info("about to apply the most violated updates");
          mv.backwards(-learningRate);
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

  public static class CoefsAndScoresAdjoints implements edu.jhu.hlt.tutils.scoring.Adjoints {
    private SearchCoefficients coefs;
    private StepScores<?> scores;
    public CoefsAndScoresAdjoints(SearchCoefficients coefs, StepScores<?> scores) {
      this.coefs = coefs;
      this.scores = scores;
    }
    @Override
    public double forwards() {
      return coefs.forwards(scores);
    }
    @Override
    public void backwards(double dErr_dForwards) {
      coefs.backwards(scores, dErr_dForwards);
    }
  }

  public Info getPredictInfo(FNParse y) {
    return getPredictInfo(y, false);
  }
  public Info getPredictInfo(FNParse y, boolean oracleArgPruning) {
    Info decInf = new Info(conf).setLike(rtConf).setDecodeCoefs();
    decInf.setLabel(y, ts);
    decInf.setTargetPruningToGoldLabels();
    if (oracleArgPruning) {
      decInf.setArgPruningUsingGoldLabelWithNoise();
    } else {
      boolean includeGoldSpansIfMissing = true;
      decInf.setArgPruningUsingSyntax(drp, includeGoldSpansIfMissing);
    }
    return decInf;
  }

  public FNParse predict(FNParse y) {
    return predictNew(y);
  }

  public FNParse predictNew(FNParse y) {
    if (AbstractTransitionScheme.DEBUG)
      Log.info("starting prediction");
    Info inf = getPredictInfo(y);
    State2<Info> s0 = ts.genRootState(inf);
    ts.flushPrimes();
    Pair<State2<Info>, DoubleBeam<State2<Info>>> i = ts.runInference(s0, inf);
    State2<Info> beamLast = i.get1();
    if (AbstractTransitionScheme.DEBUG && DEBUG_SEARCH_FINAL_SOLN) {
      Log.info("decode terminal state: (overfit=" + ts.useOverfitFeatures + ")");
      beamLast.getRoot().show(System.out);
    }
    return ts.decode(beamLast);
  }

  public FNParse predictOld(FNParse y) {
    Log.warn("using old predict method");
    timer.start("predict");
    Info decInf = getPredictInfo(y);
    FNParse yhat = State.runInference2(decInf);
    timer.stop("predict");
    return yhat;
  }


  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    config.put("beamSize", "1");
    config.put("forceLeftRightInference", "false");
    config.put("perceptron", "false");
    config.put("maxViolation", "true"); // only takes effect when perceptron=true

    boolean backToBasics = false;
    AbstractTransitionScheme.DEBUG = false || backToBasics;

    // Sort parses by number of frames so that small (easy to debug/see) examples come first
    List<FNParse> ys = State.getParse();
    Collections.sort(ys, new Comparator<FNParse>() {
      @Override
      public int compare(FNParse o1, FNParse o2) {
        return State.numItems(o1) - State.numItems(o2);
      }
    });

    Random rand = new Random(config.getInt("seed", 9001));
    File workingDir = config.getOrMakeDir("workingDir", new File("/tmp/fmodel-wd-debug"));
    FModel m = new FModel(new RTConfig("rtc", workingDir, rand), DeterministicRolePruning.Mode.XUE_PALMER_HERMANN);
    m.ts.useGlobalFeats = false;
    m.rtConf.oracleMode = OracleMode.RAND_MIN;

    m.ts.useOverfitFeatures = true;
    for (FNParse y : ys) {
      if (y.numFrameInstances() == 0)
        continue;
      if (State.numItems(y) < 2)
        continue;

      // We seem to be having some problems with lock-in (bad initialization) :)
      // We can get every example right if we start from 0 weights.
      m.ts.zeroOutWeights();
      m.ts.flushAlphabet();

//      // skipping to interesting example...
//      if (!y.getSentence().getId().equals("FNFUTXT1272003"))
//        continue;

      Log.info("working on: " + y.getId()
          + " crRoles=" + y.hasContOrRefRoles()
          + " numFI=" + y.numFrameInstances()
          + " numItems=" + State.numItems(y));

      if (backToBasics) {
        Update u = m.getUpdate(y);
        u.apply(1);
        for (int i = 0; i < 5; i++)
          m.getUpdate(y).apply(1);
        AbstractTransitionScheme.DEBUG = true;
        AbstractTransitionScheme.DEBUG_SEARCH = true;
        FNParse yhat = m.predict(y);
        SentenceEval se = new SentenceEval(y, yhat);
        Map<String, Double> r = BasicEvaluation.evaluate(Arrays.asList(se));
        double f1 = r.get("ArgumentMicroF1");
        Log.info("result: " + y.getSentence().getId() 
            + " f1=" + f1
            + " p=" + r.get("ArgumentMicroPRECISION")
            + " r=" + r.get("ArgumentMicroRECALL"));
        return;
      }

      // Check learning
      int c = 0, clim = 30;
      int updatesPerPredict = 6;
      double maxF1 = 0;
      for (int i = 0; i < clim * 5; i++) {

        for (int j = 0; j < updatesPerPredict; j++) {
          Update u = m.getUpdate(y);
          u.apply(1);
        }

        // Check k upon creation of TFKS
        TFKS.dbgFrameIndex = m.getPredictInfo(y).getConfig().frPacking.getFrameIndex();

        FNParse yhat = m.predict(y);
        SentenceEval se = new SentenceEval(y, yhat);
        Map<String, Double> r = BasicEvaluation.evaluate(Arrays.asList(se));
        double f1 = r.get("ArgumentMicroF1");
        Log.info("result: " + y.getSentence().getId() + " " + i
            + " f1=" + f1
            + " p=" + r.get("ArgumentMicroPRECISION")
            + " r=" + r.get("ArgumentMicroRECALL"));
//        Log.info(r);
        maxF1 = Math.max(f1, maxF1);
        if (f1 == 1) {
          c++;
          if (c == clim) break;
        } else {
          c = 0;
        }
      }
      if (c < clim) {
        AbstractTransitionScheme.DEBUG = true;
        FNParseTransitionScheme.DEBUG_FEATURES = true;
        LabelIndex.DEBUG_COUNTS = true;
        m.getUpdate(y).apply(1);
        AbstractTransitionScheme.DEBUG_SEARCH = true;
        m.predict(y);
        throw new RuntimeException();
      }
    }
  }

}
