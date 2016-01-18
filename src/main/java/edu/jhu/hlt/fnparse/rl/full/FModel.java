package edu.jhu.hlt.fnparse.rl.full;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.RolePacking;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.data.propbank.PropbankReader;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LabelIndex;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.TemplateDescriptionParsingException;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures.Item;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.FeatureSet;
import edu.jhu.hlt.fnparse.features.precompute.InformationGainProducts.BaseTemplates;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full.weights.ProductIndexAdjoints;
import edu.jhu.hlt.fnparse.rl.full2.AbstractTransitionScheme;
import edu.jhu.hlt.fnparse.rl.full2.AbstractTransitionScheme.PerceptronUpdateMode;
import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.fnparse.rl.full2.FNParseTransitionScheme;
import edu.jhu.hlt.fnparse.rl.full2.LLSSPatF;
import edu.jhu.hlt.fnparse.rl.full2.LLTVN;
import edu.jhu.hlt.fnparse.rl.full2.Node2;
import edu.jhu.hlt.fnparse.rl.full2.State2;
import edu.jhu.hlt.fnparse.rl.full2.TFKS;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer.RTConfig;
import edu.jhu.hlt.fnparse.util.FNDiff;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.prim.tuple.Pair;

/**
 * Don't ask me why its called FModel. This is is a wrapper around {@link State}.
 *
 * @author travis
 */
public class FModel implements Serializable {
  private static final long serialVersionUID = -3155569129086851946L;

  public static final boolean CACHE_FLATTEN = true;

  public static boolean DEBUG_SEARCH_FINAL_SOLN = true;
  public static boolean DEBUG_ORACLE_MV_CONF = true;
  public static boolean DEBUG_HINGE_COMPUTATION = true;

  public static boolean HIJACK_GET_UPDATE_FOR_DEBUG = false;

  private MultiTimer.ShowPeriodically timer;

  private Config conf;
  private RTConfig rtConf;

  // This may be null in which case we will assume that any incoming FNParses
  // will have their featuresAndSpans:CachedFeatures.Item fields set.
  private DeterministicRolePruning drp;

  /*
   * trainWeights are weights trained on separate shards using distributed
   * perceptron training:
   *   http://www.cslu.ogi.edu/~bedricks/courses/cs506-pslc/articles/week3/dpercep.pdf
   * testWeights tracks the average/mixed weights of the training weights.
   */
  private FNParseTransitionScheme testWeights;      // average of train
  private FNParseTransitionScheme[] trainWeights;   // indexed by shard

  private boolean maxViolation;
  public PerceptronUpdateMode perceptronUpdateMode;

  /**
   * @param config
   * @param pruningMode may be null if you want use {@link FNParse#featuresAndSpans}
   */
  public FModel(
      RTConfig config,
      DeterministicRolePruning.Mode pruningMode,
      int numShards) {

    ExperimentProperties p = ExperimentProperties.getInstance();
    FrameIndex fi;
    if (p.getBoolean("propbank")) {
      fi = FrameIndex.getPropbank();
    } else {
      fi = FrameIndex.getFrameNet();
    }

    rtConf = config;
    maxViolation = true;
    perceptronUpdateMode = PerceptronUpdateMode.MAX_VIOLATION;
    // OracleMode is what modulates behavior
    Log.info("[main] perceptron=" + maxViolation
        + " mode=" + perceptronUpdateMode
        + " oracleMode=" + rtConf.oracleMode);

    conf = new Config();
    conf.frPacking = new FrameRolePacking(fi);
    conf.rPacking = new RolePacking(fi);
    conf.primes = new PrimesAdapter(new Primes(p), conf.frPacking);
    conf.rand = new Random(9001);

    if (pruningMode != null)
      drp = new DeterministicRolePruning(pruningMode, null, null);
    else
      drp = null;
    timer = new MultiTimer.ShowPeriodically(15);

    Primes primes = new Primes(ExperimentProperties.getInstance());
    CFLike params = null;
//    ts = new FNParseTransitionScheme(params, primes);

    testWeights = new FNParseTransitionScheme(params, primes);
    trainWeights = new FNParseTransitionScheme[numShards];
    for (int i = 0; i < numShards; i++)
      trainWeights[i] = new FNParseTransitionScheme(params, primes);
  }

//  public FNParseTransitionScheme getTransitionSystem() {
//    return ts;
//  }
  public FNParseTransitionScheme getAverageWeights() {
    return testWeights;
  }
  public FNParseTransitionScheme getShardWeights(Shard shard) {
    if (shard.getNumShards() != trainWeights.length)
      throw new IllegalArgumentException("shard=" + shard);
    return trainWeights[shard.getShard()];
  }
  public int getNumShards() {
    return trainWeights.length;
  }

  public void combineWeightShards(boolean redistribute) {
    Log.info("[main] combining the average based on "
        + trainWeights.length + " independent perceptrons"
        + " redistribute=" + redistribute);

    // Set w for testWeights equal to the average for each shard's current w
    // Set u for testWeights equal to the sum of each shard's u (average over all history)
    testWeights.zeroOutWeights(false);
    double coef = 1d / trainWeights.length;
    for (int i = 0; i < trainWeights.length; i++)
      testWeights.addWeightsAndAverage(coef, trainWeights[i]);

    // Re-distribute the average as the initial weights for each shard
    if (redistribute) {
      for (int i = 0; i < trainWeights.length; i++) {
        boolean includeWeightSums = false;
        trainWeights[i].zeroOutWeights(includeWeightSums);
        trainWeights[i].addWeights(1, testWeights);
      }
    }
  }

  public void zeroWeights(boolean includeAverage) {
    boolean includeWeightSums = true;
    if (includeAverage)
      testWeights.zeroOutWeights(includeWeightSums);
    for (int i = 0; i < trainWeights.length; i++)
      trainWeights[i].zeroOutWeights(includeWeightSums);
  }

  public void setConfig(Config c) {
    this.conf = c;
  }

  public Config getConfig() {
    return conf;
  }

  public void setCachedFeatures(CFLike cf) {
    assert cf != null;
//    ts.setCachedFeatures(cf);
    testWeights.setCachedFeatures(cf);
    for (FNParseTransitionScheme ts : trainWeights)
      ts.setCachedFeatures(cf);
  }

  private Pair<Info, Info> getOracleAndMvInfo(FNParse y, FNParseTransitionScheme ts) {
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
    oracleInf.setArgPruning(drp, includeGoldSpansIfMissing, mvInf);
    timer.stop("update.setup.argPrune");

    if (AbstractTransitionScheme.DEBUG && DEBUG_ORACLE_MV_CONF) {
      Log.info("oracleInf: " + oracleInf);
      Log.info("mvInf: " + mvInf);
    }

    return new Pair<>(oracleInf, mvInf);
  }

  public Update getUpdate(FNParse y, FNParseTransitionScheme ts) {
    Pair<Info, Info> ormv = getOracleAndMvInfo(y, ts);
    Info oracleInf = ormv.get1();
    Info mvInf = ormv.get2();

    if (maxViolation) {
      timer.start("update.perceptron");
      ts.flushPrimes();
      State2<Info> s0 = ts.genRootState(oracleInf);
      Update u = ts.perceptronUpdate(s0, perceptronUpdateMode, rtConf.oracleMode);
      timer.stop("update.perceptron");
      return u;
    }

    timer.start("update.oracle");
    if (AbstractTransitionScheme.DEBUG)
      Log.info("doing oracle search...");
    AbstractTransitionScheme.DEBUG_ORACLE_FN = true;
    ts.flushPrimes();
    Pair<State2<Info>, DoubleBeam<State2<Info>>> oracleS =
        ts.runInference(ts.genRootState(oracleInf), oracleInf);
    AbstractTransitionScheme.DEBUG_ORACLE_FN = false;
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
      Log.info("oracle terminal state: (overfit=" + ts.featOverfit + ")");
      oracleSt.getRoot().show(System.out);
      Log.info("MV terminal state: (overfit=" + ts.featOverfit + ")");
      mvSt.getRoot().show(System.out);
    }

    if (oracleSc.getLoss().maxLoss() > 0) {
      if (badThings++ % 30 == 0) {
        System.err.println("BAD ORACLE FINAL STATE:");
        oracleSt.getRoot().show(System.err);
      }
      System.err.println("oracle has loss: " + oracleSc.getLoss());
      System.err.println("mode="+ this.rtConf.oracleMode);
      System.err.println("mvInf=" + mvInf);
      System.err.println("oracleInf=" + oracleInf);
    }

    return buildUpdate(oracleB, mvB, ts, false);
  }
  private int badThings = 0;

  private Update buildUpdate(
      CoefsAndScoresAdjoints oracle,
      CoefsAndScoresAdjoints mv,
      FNParseTransitionScheme ts,
      boolean ignoreHinge) {

    assert oracle.scores.getLoss().noLoss() : "oracle has loss: " + oracle.scores.getLoss();
    double a = mv.scores.getModel().forwards() + mv.scores.getLoss().maxLoss();
    double b = oracle.scores.getModel().forwards() + oracle.scores.getLoss().maxLoss();
    final double hinge = Math.max(0, a - b);

    if (AbstractTransitionScheme.DEBUG &&
        (DEBUG_HINGE_COMPUTATION || DEBUG_SEARCH_FINAL_SOLN || AbstractTransitionScheme.DEBUG_PERCEPTRON)) {
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

          ts.maybeApplyL2Reg();

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

  public Info getPredictInfo(FNParse y, FNParseTransitionScheme ts) {
    return getPredictInfo(y, ts, false);
  }
  public Info getPredictInfo(FNParse y, FNParseTransitionScheme ts, boolean oracleArgPruning) {
    Info decInf = new Info(conf).setLike(rtConf).setDecodeCoefs();
    decInf.setLabel(y, ts);
    decInf.setTargetPruningToGoldLabels();
    if (oracleArgPruning) {
      decInf.setArgPruningUsingGoldLabelWithNoise();
    } else {
      boolean includeGoldSpansIfMissing = false;
      decInf.setArgPruning(drp, includeGoldSpansIfMissing);
    }
    return decInf;
  }

  public FNParse predict(FNParse y) {
    return predict(y, getAverageWeights());
  }

  public FNParse predict(FNParse y, FNParseTransitionScheme ts) {
    if (AbstractTransitionScheme.DEBUG)
      Log.info("starting prediction");
    timer.start("predictNew");
    Info inf = getPredictInfo(y, ts);
    State2<Info> s0 = ts.genRootState(inf);
    ts.flushPrimes();
    Pair<State2<Info>, DoubleBeam<State2<Info>>> i = ts.runInference(s0, inf);
    State2<Info> beamLast = i.get1();
    if (AbstractTransitionScheme.DEBUG && DEBUG_SEARCH_FINAL_SOLN) {
      Log.info("decode terminal state: (overfit=" + ts.featOverfit + ")");
      beamLast.getRoot().show(System.out);
    }
    FNParse yhat = ts.decode(beamLast);
    timer.stop("predictNew");
    return yhat;
  }

  State2<Info> oracleS(FNParse y, FNParseTransitionScheme ts) {
    assert !maxViolation;
    AbstractTransitionScheme.DEBUG_ORACLE_FN = true;
    Pair<Info, Info> ormv = getOracleAndMvInfo(y, ts);
    Info oracleInf = ormv.get1();
    if (AbstractTransitionScheme.DEBUG)
      Log.info("doing oracle search...");
    ts.flushPrimes();
    Pair<State2<Info>, DoubleBeam<State2<Info>>> oracleS =
        ts.runInference(ts.genRootState(oracleInf), oracleInf);
    State2<Info> s = oracleS.get1();
    AbstractTransitionScheme.DEBUG_ORACLE_FN = false;
    return s;
  }
  FNParse oracleY(FNParse y, FNParseTransitionScheme ts) {
    return ts.decode(oracleS(y, ts));
  }

  State2<Info> mostViolatedS(FNParse y, FNParseTransitionScheme ts) {
    assert !maxViolation;
    Pair<Info, Info> ormv = getOracleAndMvInfo(y, ts);
    Info mvInf = ormv.get2();
    if (AbstractTransitionScheme.DEBUG)
      Log.info("doing most violated search...");
    ts.flushPrimes();
    Pair<State2<Info>, DoubleBeam<State2<Info>>> mvS =
        ts.runInference(ts.genRootState(mvInf), mvInf);
    State2<Info> s = mvS.get1();
    return s;
  }
  FNParse mostVoilatedY(FNParse y, FNParseTransitionScheme ts) {
    return ts.decode(mostViolatedS(y, ts));
  }


  public static FModel getFModel(ExperimentProperties config) {
    Random rand = new Random(config.getInt("seed", 9001));
    File workingDir = config.getOrMakeDir("workingDir", new File("/tmp/fmodel-wd-debug"));

//    FModel m = new FModel(new RTConfig("rtc", workingDir, rand), DeterministicRolePruning.Mode.XUE_PALMER_HERMANN);
    // CachedFeatures version
//    int numShards = 1;
    int numShards = config.getInt("shards");
    FModel m = new FModel(new RTConfig("rtc", workingDir, rand), null, numShards);

//    m.ts.useGlobalFeats = false;
//    m.rtConf.oracleMode = OracleMode.MIN;
//    m.ts.useOverfitFeatures = false;

    return m;
  }

  public static void main1(String[] args) {
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

    FModel m = getFModel(config);

    // This code was written before introducing multiple FNParseTransitionSystems
    // for distributed training. Therefore I will use the testWeights directly
    // as the weights we are manipulating. This is not how RerankerTrainer
    // will interface with FModel.
    FNParseTransitionScheme ts = m.testWeights;

    for (FNParse y : ys) {
      if (y.numFrameInstances() == 0)
        continue;
      if (State.numItems(y) < 2)
        continue;

      // We seem to be having some problems with lock-in (bad initialization) :)
      // We can get every example right if we start from 0 weights.
      ts.zeroOutWeights(true);
      ts.flushAlphabet();

//      // skipping to interesting example...
//      if (!y.getSentence().getId().equals("FNFUTXT1272003"))
//        continue;

      Log.info("working on: " + y.getId()
          + " crRoles=" + y.hasContOrRefRoles()
          + " numFI=" + y.numFrameInstances()
          + " numItems=" + State.numItems(y));

      if (backToBasics) {
        Update u = m.getUpdate(y, ts);
        u.apply(1);
        for (int i = 0; i < 5; i++)
          m.getUpdate(y, ts).apply(1);
        AbstractTransitionScheme.DEBUG = true;
        AbstractTransitionScheme.DEBUG_SEARCH = true;
        FNParse yhat = m.predict(y, ts);
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
          Update u = m.getUpdate(y, ts);
          u.apply(1);
        }

        // Check k upon creation of TFKS
        TFKS.dbgFrameIndex = m.getPredictInfo(y, ts).getConfig().frPacking.getFrameIndex();

        FNParse yhat = m.predict(y, ts);
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
        m.getUpdate(y, ts).apply(1);
        AbstractTransitionScheme.DEBUG_SEARCH = true;
        m.predict(y, ts);
        throw new RuntimeException();
      }
    }
  }

  public static void main(String[] args) throws Exception {
    main3(args);
//    main2(args);
//    main1(args);
  }

  public interface CFLike {
    public List<ProductIndex> getFeaturesNoModulo(Sentence sent, Span t, Span s);
  }
  public static class SimpleCFLike implements CFLike {
    private Map<Pair<Sentence, SpanPair>, List<ProductIndex>> sentTS2feats;
    private List<FNParse> parses;

    private Map<Sentence, Item> s2i;
    private int[][] featureSet; // TODO initialize
    private int[] template2cardinality;

    public SimpleCFLike(List<CachedFeatures.Item> items) {
      Object old;
      if (!CACHE_FLATTEN)
        sentTS2feats = new HashMap<>();
      parses = new ArrayList<>();
      s2i = new HashMap<>();
      for (CachedFeatures.Item i : items) {
        FNParse y = i.getParse();
        y.featuresAndSpans = i;  // evil
        parses.add(y);
        Sentence s = y.getSentence();
        old = s2i.put(s, i);
        assert old == null;
        if (!CACHE_FLATTEN) {
          Iterator<Pair<SpanPair, BaseTemplates>> btIter = i.getFeatures();
          while (btIter.hasNext()) {
            Pair<SpanPair, BaseTemplates> st2feats = btIter.next();
            List<ProductIndex> feats2 = bt2pi(st2feats.get2());
            Pair<Sentence, SpanPair> key = new Pair<>(s, st2feats.get1());
            old = sentTS2feats.put(key, feats2);
            assert old == null;
          }
        }
      }
    }

    public void setFeatureset(File featureSetFile, BiAlph bialph) {
      Log.info("[main] using featureSetFile=" + featureSetFile.getPath()
        + " and bialph=" + bialph.getSource().getPath()
        + " and code close to CachedFeatures.Params");
      template2cardinality = bialph.makeTemplate2Cardinality();
      featureSet = FeatureSet.getFeatureSet2(featureSetFile, bialph);

      // Cache InformationGainProducts.flatten
      if (CACHE_FLATTEN) {
        Log.info("caching flatten operation for " + featureSet.length + " features");
        for (Item i : s2i.values())
          i.convertToFlattenedRepresentation(featureSet, template2cardinality);
        Log.info("done caching flatten operation");
      }
    }

    public List<FNParse> getParses() {
      return parses;
    }

    @Override
    public List<ProductIndex> getFeaturesNoModulo(Sentence sent, Span t, Span s) {
      List<ProductIndex> feats;
      if (featureSet != null) {
        // Try to match as closely as possible how CachedFeatures.Params get features
        Item cur = s2i.get(sent);
        feats = cur.getFlattenedCachedFeatures(t, s);
        if (feats == null)
          feats = CachedFeatures.statelessGetFeaturesNoModulo(sent, t, s, cur, featureSet, template2cardinality);
      } else {
        // This was the simplest way...
        Pair<Sentence, SpanPair> key = new Pair<>(sent, new SpanPair(t, s));
        feats = sentTS2feats.get(key);

        assert false : "this is a dead code path, make sure you call setFeatureset";
      }
      assert feats != null;
      return feats;
    }
  }

  // TODO This does not do feature sets! This just takes templates rather than their products.
  // CachedFeatures.getFeaturesNoModulo uses InformationGainProducts.flatten
  public static List<ProductIndex> bt2pi(BaseTemplates bt) {
    int n = bt.size();
    List<ProductIndex> pi = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      // TODO lookup card from bt.getTemplate(i) and BiAlph?
      ProductIndex t = new ProductIndex(i, n);
      int f = bt.getValue(i);
      ProductIndex p = t.destructiveProd(f);
      pi.add(p);
    }
    return pi;
  }

  @SuppressWarnings("unchecked")
  public static List<CachedFeatures.Item> fooMemo() {
    File f = new File("/tmp/fooMemo");
    f = ExperimentProperties.getInstance().getFile("fooMemoFile", f);
    if (f.isFile()) {
      FileUtil.VERBOSE = true;
      return (List<CachedFeatures.Item>) FileUtil.deserialize(f);
    }
    List<CachedFeatures.Item> l = foo();
    FileUtil.serialize(l, f);
    return l;
  }

  public static List<CachedFeatures.Item> foo() {
    ExperimentProperties config = ExperimentProperties.getInstance();

    // Read some features out of one file eagerly
    File ff = new File("data/debugging/coherent-shards-filtered-small/features/shard0.txt");
    ff = config.getExistingFile("fooFeatureFile", ff);
    boolean templatesSorted = true;
    Iterable<FeatureFile.Line> itr = FeatureFile.getLines(ff, templatesSorted);
//    String x = StreamSupport.stream(itr.spliterator(), false)
//      .collect(Collectors.groupingBy(FeatureFile.Line::getSentenceId));
//    Collectors.groupingBy(FeatureFile.Line::getSentenceId, LinkedHashMap::new, Collectors.toList())
    Iterator<List<FeatureFile.Line>> bySent = new GroupBy<>(itr.iterator(), FeatureFile.Line::getSentenceId);

    // We also need the FNParses
    // Just read in the dev data.
    Map<String, FNParse> parseById = new HashMap<>();
    ParsePropbankData.Redis propbankAutoParses = null;
    PropbankReader pbr = new PropbankReader(config, propbankAutoParses);
//    for (FNParse y : pbr.getDevData()) {
    for (FNParse y : pbr.getTrainData()) {
      FNParse old = parseById.put(y.getId(), y);
      assert old == null;
    }
    Log.info("[main] parseById.size()=" + parseById.size());

    // Perform join
    List<CachedFeatures.Item> all = new ArrayList<>();
    int skipped = 0;
    while (bySent.hasNext()) {
      List<FeatureFile.Line> feats = bySent.next();
      String id = feats.get(0).getSentenceId();
      FNParse y = parseById.get(id);
      if (y == null) {
        // This is a parse which is in the feature file but not in the dev set,
        // skip it
        skipped++;
        continue;
      }
      CachedFeatures.Item i = new CachedFeatures.Item(y);
      for (FeatureFile.Line l : feats) {
        Span t = l.getTarget();
        Span s = l.getArgSpan();
        BitSet relTemplates = null; // null means all
        boolean storeTemplates = true;
        BaseTemplates bt = new BaseTemplates(relTemplates, l.getLine(), storeTemplates);
        i.setFeatures(t, s, bt);
      }
      all.add(i);
    }
    Log.info("[main] all.size=" + all.size() + " skipped=" + skipped);

    return all;
  }

  /*
   * This isn't working... CachedFeatures is too complex due to its parallel
   * nature and its difficult to figure out if something is a timing issue or
   * why its wrong in the first place.
   */
  // Check that we get convergence with cheating features
  public static void main2(String[] args) throws TemplateDescriptionParsingException, InterruptedException {
    ExperimentProperties config = ExperimentProperties.init(args);
    config.put("oracleMode", "MIN");
    config.put("forceLeftRightInference", "false"); // actually whether you sort your eggs or not...
    config.put("perceptron", "true"); // always keep true
    config.put("useGlobalFeatures", "true");
    config.put("beamSize", "1");

    List<CachedFeatures.Item> stuff = fooMemo();
    SimpleCFLike cfLike = new SimpleCFLike(stuff);

    // Try to mimic the code in CachedFeatures.Params.
    // This is the data needed to create the state that CachedFeatures.Params has.
    boolean mimicRT = true;
    if (mimicRT) {
      File dd = new File("data/debugging/");
      BiAlph bialph = new BiAlph(new File(dd, "coherent-shards-filtered-small/alphabet.txt"), LineMode.ALPH);
      File featureSetFile = new File(dd, "propbank-8-40.fs");
      cfLike.setFeatureset(featureSetFile, bialph);
    }

    boolean justOracle = false;

    SentenceEval se;
    Map<String, Double> r;
    double f1;

    FModel m = getFModel(config);
    m.setCachedFeatures(cfLike);

    // This code was written before introducing multiple FNParseTransitionSystems
    // for distributed training. Therefore I will use the testWeights directly
    // as the weights we are manipulating. This is not how RerankerTrainer
    // will interface with FModel.
    FNParseTransitionScheme ts = m.testWeights;

    Log.info("[main] m.ts.useGlobalFeatures=" + ts.useGlobalFeats);

    int checked = 0;
    for (FNParse y : cfLike.getParses()) {
      if (y.hasContOrRefRoles())
        continue;
      checked++;

      Log.info("working on: " + y.getId()
          + " crRoles=" + y.hasContOrRefRoles()
          + " numFI=" + y.numFrameInstances()
          + " numItems=" + State.numItems(y));
      System.out.println(new SentenceEval(y, y));

      ts.setParamsToAverage();

      if (!config.getBoolean("perceptron")) {
        // make sure that oracle can get F1=1 regardless of model scores.
        Log.info("testing the oracle");
        FNParse yOracle = m.oracleY(y, ts);
        se = new SentenceEval(y, yOracle);
        r = BasicEvaluation.evaluate(Arrays.asList(se));
        f1 = r.get("ArgumentMicroF1");
        Log.info("ORACLE TEST result: " + y.getSentence().getId()
            + " f1=" + f1
            + " p=" + r.get("ArgumentMicroPRECISION")
            + " r=" + r.get("ArgumentMicroRECALL"));
        if (f1 < 1) {
          Log.warn("oracle with non-perfect f1=" + f1 + ":");
          m.oracleS(y, ts).getRoot().show(System.err);
        }
        assert f1 == 1;
        if (justOracle)
          continue;
      }

//      // We seem to be having some problems with lock-in (bad initialization) :)
//      // We can get every example right if we start from 0 weights.
//      m.ts.zeroOutWeights();
//      m.ts.flushAlphabet();

      Log.info("testing learning");
      double lr = 0.05;
      int updates = 0;
      int lim = 2;
      int passed = 0;
      int enough = 3;
      FNParse yhat = null;
      for (int tryy = 0; tryy < 30 && passed < enough; tryy++) {
        // Do some learning
        double hinge = 0;
        for (int j = 0; j < lim; j++) {
          hinge += m.getUpdate(y, ts).apply(lr);
          updates++;
        }
        hinge /= updates;

        System.out.println("wHatch: " + ts.wHatch);
        System.out.println("wSquash: " + ts.wSquash);
        System.out.println("wGlobal: " + ts.wGlobal);
        System.out.println("hinge: " + hinge);

        // Test
        yhat = m.predict(y, ts);
        se = new SentenceEval(y, yhat);
        r = BasicEvaluation.evaluate(Arrays.asList(se));
        f1 = r.get("ArgumentMicroF1");
        Log.info("result: " + y.getSentence().getId()
            + " try=" + tryy
            + " updates=" + updates
            + " f1=" + f1
            + " p=" + r.get("ArgumentMicroPRECISION")
            + " r=" + r.get("ArgumentMicroRECALL"));
        if (f1 == 1) {
          passed++;
        } else {
//          assert false;
          passed = 0;
          lim = (int) (lim * 1.2 + 2);
        }
      }
      if (passed < enough) {
        Log.warn("HERE IS YOUR FAILURE!!!");
        AbstractTransitionScheme.DEBUG = true;
//        FNParseTransitionScheme.DEBUG_DECODE = true;
        FModel.DEBUG_SEARCH_FINAL_SOLN = true;
        LLSSPatF.DEBUG_SHOW_BACKWARDS = true;

        // Check an update
        m.getUpdate(y, ts).apply(lr);

        // Show a run of the decoder
        m.predict(y, ts);

        if (!config.getBoolean("perceptron")) {
          // Run the oracle by itself
          Log.info("about to run most violated search for the last time");
          State2<Info> as = m.oracleS(y, ts);
          Log.info("about to decode oracle state for the last time");
          FNParse a = ts.decode(as);
          showLoss(y, a, "oracle");

          // Run most violated by itself
          Log.info("about to run most violated search for the last time");
          State2<Info> bs = m.mostViolatedS(y, ts);
          Log.info("about to decode most violated state for the last time");
          FNParse b = ts.decode(bs);
          showLoss(y, b, "MV");
        }
      }
      assert passed == enough : FNDiff.diffArgs(y, yhat, true);
    }

    Log.info("done, checked " + checked + " parses");
  }

  /**
   * Prints info on how much work is being done every time inference is run.
   */
  public static void featureAudit(FNParse y, FModel m, FNParseTransitionScheme ts, boolean predict) {

    // Check possible for each T/F node
    Info inf = m.getPredictInfo(y, ts);
    Node2 n = ts.genRootNode(inf);
    int X = 0;
    for (LLTVN cur = n.eggs; cur != null; cur = cur.cdr())
      X += cur.car().numPossible;

    ProductIndexAdjoints.zeroCounters();
    AveragedPerceptronWeights.zeroCounters();
    FNParseTransitionScheme.zeroCounters();
    AbstractTransitionScheme.zeroCounters();
    DoubleBeam.zeroCounters();
    Info.zeroCounters();

    ts.zeroOutWeights(true);

    if (predict) {
      Log.info("predict");
      m.predict(y, ts);
    } else {
      Log.info("update");
      m.getUpdate(y, ts).apply(1);
    }

    int nFI  = y.numFrameInstances();
    int nI = State.numItems(y);
    int nTok = y.getSentence().size();
    int Ksum = 0, Kmax = 0;
    for (FrameInstance fi : y.getFrameInstances()) {
      int k = fi.getFrame().numRoles();
      Ksum += k;
      Kmax = Math.max(Kmax, k);
    }
    Log.info("id=" + y.getId() + " nFI=" + nFI + " nI=" + nI + " nTok=" + nTok
        + " Ksum=" + Ksum + " Kmax=" + Kmax + " X=" + X);
    ProductIndexAdjoints.logCounters();
    AveragedPerceptronWeights.logCounters();
    FNParseTransitionScheme.logCounters();
    AbstractTransitionScheme.logCounters();
    DoubleBeam.logCounters();
    Info.logCounters();
  }

  public static boolean overlappingIds2(Collection<CachedFeatures.Item> a, Collection<CachedFeatures.Item> b) {
    List<FNParse> aa = new ArrayList<>();
    List<FNParse> bb = new ArrayList<>();
    for (CachedFeatures.Item x : a) aa.add(x.getParse());
    for (CachedFeatures.Item x : b) bb.add(x.getParse());
    return overlappingIds(aa, bb);
  }
  public static boolean overlappingIds(Collection<FNParse> a, Collection<FNParse> b) {
    // Ensure a.size <= b.size
    if (a.size() > b.size()) {
      Collection<FNParse> t = a; a = b; b = t;
    }
    Set<String> s = new HashSet<>();
    for (FNParse y : a)
      s.add(y.getId());
    for (FNParse y : b)
      if (s.contains(y.getId()))
        return true;
    Log.info("no overlap between " + a.size() + " and " + b.size() + " ids");
    return false;
  }

  // Lets see if we can get decent performance on LOOCV
  public static void main3(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    config.putIfAbsent("oracleMode", "RAND");
    config.putIfAbsent("beamSize", "1");
    config.putIfAbsent("oneAtATime", "" + TFKS.F);

//    config.putIfAbsent("ANY_GLOBALS", "false");

//    config.putIfAbsent("sortEggsMode", "NONE");
    config.putIfAbsent("sortEggsMode", "BY_MODEL_SCORE");
//    config.putIfAbsent("sortEggsMode", "BY_EXPECTED_UTILITY");

//    config.putIfAbsent("sortEggsKmaxS", "false");
    config.putIfAbsent("sortEggsKmaxS", "true");

    Log.info("oracleMode=" + config.getString("oracleMode"));
    Log.info("beamSize=" + config.getInt("beamSize"));

//    config.put("globalFeatArgLocSimple", "false");
//    config.put("globalFeatNumArgs", "false");
//    config.put("globalFeatRoleCoocSimple", "false");

    AbstractTransitionScheme.DEBUG = false;
    boolean pedantic = true;

    FModel m = getFModel(config);

    // This code was written before introducing multiple FNParseTransitionSystems
    // for distributed training. Therefore I will use the testWeights directly
    // as the weights we are manipulating. This is not how RerankerTrainer
    // will interface with FModel.
    FNParseTransitionScheme ts = m.testWeights;

    List<CachedFeatures.Item> stuff = fooMemo();
//    stuff = stuff.subList(0, 50);

    SimpleCFLike cfLike = new SimpleCFLike(stuff);
    m.setCachedFeatures(cfLike);

    // Try to mimic the code in CachedFeatures.Params.
    // This is the data needed to create the state that CachedFeatures.Params has.
    boolean mimicRT = true;
    if (mimicRT) {
      File dd = new File("data/debugging/");
      File bf = config.getExistingFile("bialph", new File(dd, "coherent-shards-filtered-small/alphabet.txt"));
      BiAlph bialph = new BiAlph(bf, LineMode.ALPH);
      File fsParent = config.getFile("featureSetParent", dd);
      int fsC = config.getInt("fsC", 8);
      int fsN = config.getInt("fsN", 640);
      File featureSetFile = config.getExistingFile("featureSet", new File(fsParent, "propbank-" + fsC + "-" + fsN + ".fs"));
      cfLike.setFeatureset(featureSetFile, bialph);
    }
    Log.info("[main] m.ts.useGlobalFeatures=" + ts.useGlobalFeats);

    boolean speedAudit = false;
    if (speedAudit) {
      int n = Math.min(stuff.size(), 15);
      for (int i = 0; i < n; i++) {
        featureAudit(stuff.get(i).parse, m, ts, true);
        featureAudit(stuff.get(i).parse, m, ts, false);
      }
      return;
    }

    int folds = 12;
    int n = stuff.size();
    for (int fold = 0; fold < folds; fold++) {
      Log.info("starting fold " + fold);

      // Training set (all data minus one instance)
      List<FNParse> train = new ArrayList<>();
      List<FNParse> test = new ArrayList<>();
      for (int i = 0; i < n; i++)
        if ((i % folds) == fold)
          test.add(stuff.get(i).parse);
        else
          train.add(stuff.get(i).parse);
      assert !overlappingIds(train, test);

      // Do some learning (few epochs)
      double maxIters = config.getInt("maxIters", 12);
      boolean zeroSumsToo = true;
      ts.zeroOutWeights(zeroSumsToo);
      ts.showWeights("after-zeroing");
      for (int i = 0; i < maxIters; i++) {
        Log.info("[main] training on " + train.size() + " examples");
        Collections.shuffle(train, m.getConfig().rand);
        for (FNParse y : train) {
          if (pedantic) Log.info("training against: " + y.getId());
          m.getUpdate(y, ts).apply(1);
        }
        assert !overlappingIds(train, test);
        showLoss(test, m, ts, "after-epoch-" + i + "-TEST");
        int tn = Math.min(100, train.size());
        showLoss(train.subList(0, tn), m, ts, "after-epoch-" + i + "-TRAIN");
      }

      // See what we get on the test example
      ts.setParamsToAverage();
      ts.showWeights("after-averaging");
      assert !overlappingIds(train, test);
      if (pedantic) 
        for (FNParse y : test)
          Log.info("testing against: " + y.getId());
      showLoss(test, m, ts, "on-fold-" + fold);
//      FNParse yhat = m.predict(yTest);
//      SentenceEval se = new SentenceEval(yTest, yhat);
//      Map<String, Double> r = BasicEvaluation.evaluate(Arrays.asList(se));
//      double f1 = r.get("ArgumentMicroF1");
//      Log.info("LOOCV result: " + yTest.getSentence().getId()
//          + " f1=" + f1
//          + " p=" + r.get("ArgumentMicroPRECISION")
//          + " r=" + r.get("ArgumentMicroRECALL"));
//      assert f1 >= 0.5;
    }
  }

  public static void showLoss2(List<CachedFeatures.Item> ys, FModel m, FNParseTransitionScheme ts, String label) {
    List<FNParse> ys2 = new ArrayList<>();
    for (CachedFeatures.Item yy : ys)
      ys2.add(yy.getParse());
    showLoss(ys2, m, ts, label);
  }

  public static void showLoss(List<FNParse> ys, FModel m, FNParseTransitionScheme ts, String label) {
    List<SentenceEval> se = new ArrayList<>();
    for (FNParse y : ys) {
      FNParse yhat = m.predict(y, ts);
      se.add(new SentenceEval(y, yhat));
    }
    Map<String, Double> r = BasicEvaluation.evaluate(se);
    double f1 = r.get("ArgumentMicroF1");
    Log.info(label + " result: "
        + " f1=" + f1
        + " p=" + r.get("ArgumentMicroPRECISION")
        + " r=" + r.get("ArgumentMicroRECALL"));
  }

  private static void showLoss(FNParse y, FNParse yhat, String label) {
    SentenceEval se = new SentenceEval(y, yhat);
    Map<String, Double> r = BasicEvaluation.evaluate(Arrays.asList(se));
    double f1 = r.get("ArgumentMicroF1");
    Log.info(label + " result: " + y.getSentence().getId()
        + " f1=" + f1
        + " p=" + r.get("ArgumentMicroPRECISION")
        + " r=" + r.get("ArgumentMicroRECALL"));
  }

}
