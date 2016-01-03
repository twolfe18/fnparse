package edu.jhu.hlt.fnparse.rl.full;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.data.propbank.PropbankReader;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.LabelIndex;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.TemplateDescriptionParsingException;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.InformationGainProducts.BaseTemplates;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full2.AbstractTransitionScheme;
import edu.jhu.hlt.fnparse.rl.full2.AbstractTransitionScheme.PerceptronUpdateMode;
import edu.jhu.hlt.fnparse.rl.full2.FNParseTransitionScheme;
import edu.jhu.hlt.fnparse.rl.full2.LLSSPatF;
import edu.jhu.hlt.fnparse.rl.full2.FNParseTransitionScheme.SortEggsMode;
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
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
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
  public static boolean DEBUG_ORACLE_MV_CONF = true;
  public static boolean DEBUG_HINGE_COMPUTATION = true;

  public static boolean HIJACK_GET_UPDATE_FOR_DEBUG = false;

  private MultiTimer.ShowPeriodically timer;

  private Config conf;
  private RTConfig rtConf;

  // This may be null in which case we will assume that any incoming FNParses
  // will have their featuresAndSpans:CachedFeatures.Item fields set.
  private DeterministicRolePruning drp;

//  private CachedFeatures cachedFeatures;
  private FNParseTransitionScheme ts;


  // LH's max violation
  private boolean maxViolation;
  public PerceptronUpdateMode perceptronUpdateMode;

  /**
   * @param config
   * @param pruningMode may be null if you want use {@link FNParse#featuresAndSpans}
   */
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

    rtConf = config;
    maxViolation = p.getBoolean("perceptron");
    perceptronUpdateMode =
        p.getBoolean("maxViolation", true)
        ? PerceptronUpdateMode.MAX_VIOLATION
            : PerceptronUpdateMode.EARLY;
    Log.info("[main] perceptron=" + maxViolation
        + " mode=" + perceptronUpdateMode
        + " oracleMode=" + rtConf.oracleMode);

    conf = new Config();
    conf.frPacking = new FrameRolePacking(fi);
    conf.primes = new PrimesAdapter(new Primes(p), conf.frPacking);

    // TODO Remove, this is deprecate (FNParseTS has weights now)
//    int l2UpdateInterval = 32;
//    int dimension = 1;  // Not using the weights in here, don't waste space
//    conf.weights = new GeneralizedWeights(conf, null, dimension, l2UpdateInterval);

    if (pruningMode != null)
      drp = new DeterministicRolePruning(pruningMode, null, null);
    else
      drp = null;
    timer = new MultiTimer.ShowPeriodically(15);

    Primes primes = new Primes(ExperimentProperties.getInstance());
//    CachedFeatures params = null;
    CFLike params = null;
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
//  public void setCachedFeatures(CachedFeatures cf) {
  public void setCachedFeatures(CFLike cf) {
//    drp.cachedFeatures = cf;
    assert cf != null;
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
    oracleInf.setArgPruning(drp, includeGoldSpansIfMissing, mvInf);
    timer.stop("update.setup.argPrune");

    if (AbstractTransitionScheme.DEBUG && DEBUG_ORACLE_MV_CONF) {
      Log.info("oracleInf: " + oracleInf);
      Log.info("mvInf: " + mvInf);
    }

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
      Log.info("oracle terminal state: (overfit=" + ts.useOverfitFeatures + ")");
      oracleSt.getRoot().show(System.out);
      Log.info("MV terminal state: (overfit=" + ts.useOverfitFeatures + ")");
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

    return buildUpdate(oracleB, mvB, false);
  }
  private int badThings = 0;

  private Update buildUpdate(CoefsAndScoresAdjoints oracle, CoefsAndScoresAdjoints mv, boolean ignoreHinge) {

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
      decInf.setArgPruning(drp, includeGoldSpansIfMissing);
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

//  public FNParse predictOld(FNParse y) {
//    Log.warn("using old predict method");
//    timer.start("predict");
//    Info decInf = getPredictInfo(y);
//    FNParse yhat = State.runInference2(decInf);
//    timer.stop("predict");
//    return yhat;
//  }

  State2<Info> oracleS(FNParse y) {
    assert !maxViolation;
    AbstractTransitionScheme.DEBUG_ORACLE_FN = true;
    Pair<Info, Info> ormv = getOracleAndMvInfo(y);
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
  FNParse oracleY(FNParse y) {
    return ts.decode(oracleS(y));
  }

  State2<Info> mostViolatedS(FNParse y) {
    assert !maxViolation;
    Pair<Info, Info> ormv = getOracleAndMvInfo(y);
    Info mvInf = ormv.get2();
    if (AbstractTransitionScheme.DEBUG)
      Log.info("doing most violated search...");
    ts.flushPrimes();
    Pair<State2<Info>, DoubleBeam<State2<Info>>> mvS =
        ts.runInference(ts.genRootState(mvInf), mvInf);
    State2<Info> s = mvS.get1();
    return s;
  }
  FNParse mostVoilatedY(FNParse y) {
    return ts.decode(mostViolatedS(y));
  }


  private static FModel getFModel(ExperimentProperties config) {
    Random rand = new Random(config.getInt("seed", 9001));
    File workingDir = config.getOrMakeDir("workingDir", new File("/tmp/fmodel-wd-debug"));

//    FModel m = new FModel(new RTConfig("rtc", workingDir, rand), DeterministicRolePruning.Mode.XUE_PALMER_HERMANN);
    // CachedFeatures version
    FModel m = new FModel(new RTConfig("rtc", workingDir, rand), null);

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

  public static void main(String[] args) throws Exception {
    main2(args);
//    main1(args);
  }

  public interface CFLike {
    public List<ProductIndex> getFeaturesNoModulo(Sentence sent, Span t, Span s);
  }
  public static class SimpleCFLike implements CFLike {
    private Map<Pair<Sentence, SpanPair>, List<ProductIndex>> sentTS2feats;
    private List<FNParse> parses;

    public SimpleCFLike(List<CachedFeatures.Item> items) {
      sentTS2feats = new HashMap<>();
      parses = new ArrayList<>();
      for (CachedFeatures.Item i : items) {
        FNParse y = i.getParse();
        y.featuresAndSpans = i;  // evil
        parses.add(y);
        Sentence s = y.getSentence();
        Iterator<Pair<SpanPair, BaseTemplates>> btIter = i.getFeatures();
        while (btIter.hasNext()) {
          Pair<SpanPair, BaseTemplates> st2feats = btIter.next();
          List<ProductIndex> feats2 = bt2pi(st2feats.get2());
          Pair<Sentence, SpanPair> key = new Pair<>(s, st2feats.get1());
          List<ProductIndex> old = sentTS2feats.put(key, feats2);
          assert old == null;
        }
      }
    }

    public List<FNParse> getParses() {
      return parses;
    }

    @Override
    public List<ProductIndex> getFeaturesNoModulo(Sentence sent, Span t, Span s) {
      Pair<Sentence, SpanPair> key = new Pair<>(sent, new SpanPair(t, s));
      List<ProductIndex> feats = sentTS2feats.get(key);
      assert feats != null;
      return feats;
    }
  }

  // TODO This does not do feature sets! This just takes templates rather than their products.
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

  public static List<CachedFeatures.Item> fooMemo() {
    File f = new File("/tmp/fooMemo");
    if (f.isFile())
      return (List<CachedFeatures.Item>) FileUtil.deserialize(f);
    List<CachedFeatures.Item> l = foo();
    FileUtil.serialize(l, f);
    return l;
  }

  public static List<CachedFeatures.Item> foo() {
    // Read some features out of one file eagerly
    File ff = new File("data/debugging/coherent-shards-filtered-small/features/shard0.txt");
    boolean templatesSorted = true;
    Iterable<FeatureFile.Line> itr = FeatureFile.getLines(ff, templatesSorted);
//    String x = StreamSupport.stream(itr.spliterator(), false)
//      .collect(Collectors.groupingBy(FeatureFile.Line::getSentenceId));
//    Collectors.groupingBy(FeatureFile.Line::getSentenceId, LinkedHashMap::new, Collectors.toList())
    Iterator<List<FeatureFile.Line>> bySent = new GroupBy<>(itr.iterator(), FeatureFile.Line::getSentenceId);

    // We also need the FNParses
    // Just read in the dev data.
    ExperimentProperties config = ExperimentProperties.getInstance();
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
  public static void main2(String[] args) throws TemplateDescriptionParsingException, InterruptedException {
    ExperimentProperties config = ExperimentProperties.init(args);
    config.put("oracleMode", "MIN");
    config.put("forceLeftRightInference", "false"); // actually whether you sort your eggs or not...
    config.put("perceptron", "true");
    config.put("useGlobalFeatures", "true");
    config.put("beamSize", "4");

//    String fs = "Word4-1-grams-between-Span2.First-and-Span2.Last-Top1000"
//        + " + Word4-2-grams-between-</S>-and-Span1.First-Top10"
//        + " + Dist-discLen5-Span1.First-Span2.Last-Top10"
//        + " + Head2-RootPath-Basic-POS-DEP-t-Top10"
//        + " + Head2-Word-Top1000"
//        + " + Span1-PosPat-FULL_POS-2-0-Cnt8"
//        + " + Dist-discLen3-Head1-Span1.Last"
//        + " + Head1-BasicLabel"
//        + " + Role1";
//    CachedFeatures cf = CachedFeatures.buildCachedFeaturesForTesting(config, fs);
//    ItemProvider ip = cf.new ItemProvider(100, true, false);

    List<CachedFeatures.Item> stuff = fooMemo();
    SimpleCFLike cfLike = new SimpleCFLike(stuff);

    boolean justOracle = false;

    SentenceEval se;
    Map<String, Double> r;
    double f1;

    FModel m = getFModel(config);
    m.setCachedFeatures(cfLike);
//    m.ts.useOverfitFeatures = true;
    Log.info("[main] m.ts.useGlobalFeatures=" + m.ts.useGlobalFeats);

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

      if (!config.getBoolean("perceptron")) {
        // make sure that oracle can get F1=1 regardless of model scores.
        Log.info("testing the oracle");
        FNParse yOracle = m.oracleY(y);
        se = new SentenceEval(y, yOracle);
        r = BasicEvaluation.evaluate(Arrays.asList(se));
        f1 = r.get("ArgumentMicroF1");
        Log.info("ORACLE TEST result: " + y.getSentence().getId()
            + " f1=" + f1
            + " p=" + r.get("ArgumentMicroPRECISION")
            + " r=" + r.get("ArgumentMicroRECALL"));
        if (f1 < 1) {
          Log.warn("oracle with non-perfect f1=" + f1 + ":");
          m.oracleS(y).getRoot().show(System.err);
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
      int updates = 0;
      int lim = 2;
      int passed = 0;
      int enough = 3;
      FNParse yhat = null;
      for (int tryy = 0; tryy < 30 && passed < enough; tryy++) {
        // Do some learning
        double hinge = 0;
        for (int j = 0; j < lim; j++) {
          hinge += m.getUpdate(y).apply(1);
          updates++;
        }
        hinge /= updates;

        System.out.println("wHatch: " + m.ts.wHatch);
        System.out.println("wSquash: " + m.ts.wSquash);
        System.out.println("wGlobal: " + m.ts.wGlobal);
        System.out.println("hinge: " + hinge);

        // Test
        yhat = m.predict(y);
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
        m.getUpdate(y).apply(1);

        // Show a run of the decoder
        m.predict(y);

        if (!config.getBoolean("perceptron")) {
          // Run the oracle by itself
          Log.info("about to run most violated search for the last time");
          State2<Info> as = m.oracleS(y);
          Log.info("about to decode oracle state for the last time");
          FNParse a = m.ts.decode(as);
          showLoss(y, a, "oracle");

          // Run most violated by itself
          Log.info("about to run most violated search for the last time");
          State2<Info> bs = m.mostViolatedS(y);
          Log.info("about to decode most violated state for the last time");
          FNParse b = m.ts.decode(bs);
          showLoss(y, b, "MV");
        }
      }
      assert passed == enough : FNDiff.diffArgs(y, yhat, true);
    }

    Log.info("done, checked " + checked + " parses");
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
