package edu.jhu.hlt.fnparse.rl.full2;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntFunction;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.RolePacking;
import edu.jhu.hlt.fnparse.data.propbank.RoleType;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.rl.full.Beam;
import edu.jhu.hlt.fnparse.rl.full.FModel;
import edu.jhu.hlt.fnparse.rl.full.FModel.CFLike;
import edu.jhu.hlt.fnparse.rl.full.Info;
import edu.jhu.hlt.fnparse.rl.full.Primes;
import edu.jhu.hlt.fnparse.rl.full.State;
import edu.jhu.hlt.fnparse.rl.full.weights.ProductIndexAdjoints;
import edu.jhu.hlt.fnparse.rl.full.weights.WeightsInfo;
import edu.jhu.hlt.fnparse.rl.full2.eggs.EggWithStaticScore;
import edu.jhu.hlt.fnparse.rl.full2.eggs.ExpectedUtilityEggSorter;
import edu.jhu.hlt.fnparse.rl.full2.eggs.SortedEggCache;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.tutils.scoring.Adjoints.Caching;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;

/**
 * An instantiation of {@link AbstractTransitionScheme} which specifies the
 * [targetSpan, frame, role, argSpan] loop order for Propbank/FrameNet prediction.
 *
 * @author travis
 */
public class FNParseTransitionScheme extends AbstractTransitionScheme<FNParse, Info> implements Serializable {
  private static final long serialVersionUID = -3778783151385695349L;

  public static boolean DEBUG_ENCODE = true;
  public static boolean DEBUG_FEATURES = false;
  public static boolean DEBUG_GEN_EGGS = false;
  public static boolean DEBUG_DECODE = false;

  public static boolean MAIN_LOGGING = true;

  public static int COUNTER_MISC = 0;
  public static int COUNTER_COMPUTE_FEATS = 0;
  public static int COUNTER_GEN_EGGS_CACHE_MISS = 0;
  public static void zeroCounters() {
    COUNTER_MISC = 0;
    COUNTER_COMPUTE_FEATS = 0;
    COUNTER_GEN_EGGS_CACHE_MISS = 0;
  }
  public static void logCounters() {
    Log.info("COUNTER_COMPUTE_FEATS=" + COUNTER_COMPUTE_FEATS
        + " COUNTER_GEN_EGGS_CACHE_MISS=" + COUNTER_GEN_EGGS_CACHE_MISS
        + " COUNTER_MISC=" + COUNTER_MISC);
  }

  public static boolean MULTI_THREADED = true;
  public MultiTimer.ShowPeriodically timer;

  public enum SortEggsMode {
    BY_EXPECTED_UTILITY,
    BY_MODEL_SCORE,
    NONE,
  }
  // NOTE: We actually don't need features which say "this is the i^th egg"
  // because the global features applied in different orders will lead to things
  // being pushed off the beam at different times.
  // This is not to say that "i^th egg" features wont help...
  public SortEggsMode sortEggsMode = SortEggsMode.NONE;
  // If true, the static score of a K hatch is the max of the static scores of
  // the S hatch actions beneath it.
  public boolean sortEggsKmaxS = true;

  public int oneAtATime = TFKS.F;

  // Does what it says. Only reason to do SQUASH(K) is to skip a whole bunch of
  // PRUNE(S)s, but I'm not sure it is worth it now given that I have no confidence
  // in this model's ability to properly score SQUASH(K)s.
  // The main problem is the interaction between onlyUseHatchWeights, meaning
  // score(SQUASH(K))=-score(HATCH(K)), and eggScoresKmaxS=true, meaning
  // score(HATCH(K))=max_S SCORE(HATCH(S)).
  public boolean forceAllKHatches = true;

  // How many S-valued eggs to look at (one hatch per) at every state?
  private int hatchesPerK;

  public boolean useGlobalFeats;
  public boolean onlyUseHatchWeights = true;    // and thus scoreHatch(n) = -scoreSquash(n) unless other special cases apply

  // Only relevant if onlyUseHatchWeights is true.
  public boolean addConstantToSquashParams = false;

  private Alphabet<TFKS> prefix2primeIdx;
  private Primes primes;

  public boolean useContRoles = false;
  public boolean useRefRoles = false;

  // Features
  private transient CFLike cachedFeatures;
  public final boolean featOverfit;
  public final boolean featProdBase;     // feature for just (t,s), center of shrinkage for others
  public final boolean featProdF;    // finer than (t,s) feature, but ignores role
  public final boolean featProdFK;   // perhaps not necessary for propbank?
  public final boolean featProdK;    // perhaps not sufficient for framenet?

  public static final ProductIndex PI_DYN = new ProductIndex(0, 8);
  public static final ProductIndex PI_STAT_T = new ProductIndex(1, 8);
  public static final ProductIndex PI_STAT_F = new ProductIndex(2, 8);
  public static final ProductIndex PI_STAT_K = new ProductIndex(3, 8);
  public static final ProductIndex PI_STAT_S = new ProductIndex(4, 8);
  public static final ProductIndex PI_STAT_S_F = new ProductIndex(5, 8);
  public static final ProductIndex PI_STAT_S_FK = new ProductIndex(6, 8);
  public static final ProductIndex PI_STAT_S_K = new ProductIndex(7, 8);

  public transient Alphabet<String> alph;  // TODO Remove!

  // Weights
  public AveragedPerceptronWeights wHatch;
  public AveragedPerceptronWeights wSquash;
  public AveragedPerceptronWeights wGlobal;

  public FNParseTransitionScheme getAverageView() {
    FNParseTransitionScheme t = new FNParseTransitionScheme(cachedFeatures, primes);
    t.wHatch = wHatch == null ? null : wHatch.averageView();
    t.wSquash = wSquash == null ? null : wSquash.averageView();
    t.wGlobal = wGlobal == null ? null : wGlobal.averageView();
    return t;
  }

  /** Does this += coef * w */
  public void addWeights(double coef, FNParseTransitionScheme w) {
    assert (wHatch == null) == (w.wHatch == null);
    assert (wSquash == null) == (w.wSquash == null);
    assert (wGlobal == null) == (w.wGlobal == null);
    if (wHatch != null)
      wHatch.addWeights(coef, w.wHatch.getInternalWeights());
    if (wSquash != null)
      wSquash.addWeights(coef, w.wSquash.getInternalWeights());
    if (wGlobal != null)
      wGlobal.addWeights(coef, w.wGlobal.getInternalWeights());
  }

  public void addWeightsAndAverage(double coef, FNParseTransitionScheme w) {
    assert (wHatch == null) == (w.wHatch == null);
    assert (wSquash == null) == (w.wSquash == null);
    assert (wGlobal == null) == (w.wGlobal == null);
    if (wHatch != null)
      wHatch.addWeightsAndAverage(coef, w.wHatch);
    if (wSquash != null)
      wSquash.addWeightsAndAverage(coef, w.wSquash);
    if (wGlobal != null)
      wGlobal.addWeightsAndAverage(coef, w.wGlobal);
  }

  public void addWeightsIntoAverage(boolean alsoZeroOutNonAvgWeights) {
    if (wHatch != null)
      wHatch.addWeightsIntoAverage(alsoZeroOutNonAvgWeights);
    if (wSquash != null)
      wSquash.addWeightsIntoAverage(alsoZeroOutNonAvgWeights);
    if (wGlobal != null)
      wGlobal.addWeightsIntoAverage(alsoZeroOutNonAvgWeights);
  }

  public void scaleWeights(double alpha) {
    boolean includeAverage = false;
    if (wHatch != null) wHatch.scale(alpha, includeAverage);
    if (wSquash != null) wSquash.scale(alpha, includeAverage);
    if (wGlobal != null) wGlobal.scale(alpha, includeAverage);
  }

  public void gaussianFill(Random r) {
    if (wHatch != null) wHatch.gaussianFill(r);
    if (wSquash != null) wSquash.gaussianFill(r);
    if (wGlobal != null) wGlobal.gaussianFill(r);
  }

  @Override
  public void calledAfterEveryUpdate() {
    if (wHatch != null) wHatch.completedObservation();
    if (wSquash != null) wSquash.completedObservation();
    if (wGlobal != null) wGlobal.completedObservation();
  }

  public void setParamsToAverage() {
    showWeights("before-param-avg");
    tstart("setParamsToAverage");
    if (wHatch != null) {
      Log.info("[main] numObservations=" + wHatch.numObervations());
      wHatch = wHatch.computeAverageWeights();
    }
    if (wSquash != null)
      wSquash = wSquash.computeAverageWeights();
    if (wGlobal != null)
      wGlobal = wGlobal.computeAverageWeights();
    showWeights("after-param-avg");
    tstop("setParamsToAverage");
  }

  private void tstart(String s) {
    if (timer != null)
      timer.start(s);
  }
  public void tstop(String s) {
    if (timer != null)
      timer.stop(s);
  }

  public void maybeApplyL2Reg() {
    throw new RuntimeException("using averaged perceptron, think again");
  }

  public FNParseTransitionScheme(CFLike cf, Primes primes) {
    Log.info("cfLike=" + cf);
    this.cachedFeatures = cf;
    this.alph = new Alphabet<>();

    prefix2primeIdx = new Alphabet<>();
    this.primes = primes;

    ExperimentProperties config = ExperimentProperties.getInstance();
    sortEggsMode = SortEggsMode.valueOf(config.getString("sortEggsMode", "BY_MODEL_SCORE"));
    sortEggsKmaxS = config.getBoolean("sortEggsKmaxS", true);
    oneAtATime = config.getInt("oneAtATime", TFKS.F);

    this.hatchesPerK = config.getInt("hatchesPerK", 1);

    this.featOverfit = config.getBoolean("featOverfit", false);

    this.featProdBase = config.getBoolean("featProdBase", false);
    this.featProdF = config.getBoolean("featProdF", false);
    this.featProdFK = config.getBoolean("featProdFK", true);
    this.featProdK = config.getBoolean("featProdK", true);

    useGlobalFeats = LLSSPatF.ARG_LOC || LLSSPatF.NUM_ARGS || LLSSPatF.ROLE_COOC;

    if (!MULTI_THREADED)
      timer = new MultiTimer.ShowPeriodically(30);

    int dimension = config.getInt("hashingTrickDim", 1 << 24);

    int numIntercept = addConstantToSquashParams ? 1 : 0;
    int numWV = 1;
    wHatch = new AveragedPerceptronWeights(dimension, numIntercept);
    if (!onlyUseHatchWeights) {
      numWV++;
      wSquash = new AveragedPerceptronWeights(dimension, 0);
    }
    if (useGlobalFeats) {
      numWV++;
      wGlobal = new AveragedPerceptronWeights(dimension, 0);
    }

    if (MAIN_LOGGING) {
      double bytes = numWV * 2 * dimension * 8;
      Log.info("[main] using " + numWV + " weight vectors (AveragedPerceptronWeights),"
          + " each of which has two vectors of length " + dimension
          + " for a total of " + (bytes / (1L<<30)) + " GB of ram");

      // Show L2Reg/learningRate for each
      Log.info("[main] wHatch=" + wHatch.summary());
      Log.info("[main] wSquash=" + (wSquash == null ? "null" : wSquash.summary()));
      Log.info("[main] wGlobal=" + (wGlobal == null ? "null" : wGlobal.summary()));
      Log.info("[main] beamSize=" + config.getInt("beamSize", 1));
      Log.info("[main] featOverfit=" + featOverfit);
      Log.info("[main] featProdBase=" + featProdBase);
      Log.info("[main] featProdF=" + featProdF);
      Log.info("[main] featProdFK=" + featProdFK);
      Log.info("[main] featProdK=" + featProdK);
      Log.info("[main] hatchesPerK=" + hatchesPerK);
      Log.info("[main] sortSByL2R=" + SortedEggCache.sortSByL2R(config));
      Log.info("[main] sortEggsMode=" + sortEggsMode);
      Log.info("[main] sortEggsKmaxS=" + sortEggsKmaxS);
      Log.info("[main] oneAtATime=" + Node2.typeName(oneAtATime));
      Log.info("[main] ARG_LOC=" + LLSSPatF.ARG_LOC);
      Log.info("[main] NUM_ARGS=" + LLSSPatF.NUM_ARGS);
      Log.info("[main] ROLE_COOC=" + LLSSPatF.ROLE_COOC);
      LLSSPatF.logGlobalFeatures(true);
    }
  }

  public long getNumBytesUsed() {
    int numWV = 0;
    if (wHatch != null) numWV++;
    if (wSquash != null) numWV++;
    if (wGlobal != null) numWV++;
    int d = wHatch.dimension();
    return numWV * d * 2 * 8;
  }

  public void showWeights() {
    Log.info("[main] wHatch=" + wHatch.summary());
    Log.info("[main] wSquash=" + (wSquash == null ? "null" : wSquash.summary()));
    Log.info("[main] wGlobal=" + (wGlobal == null ? "null" : wGlobal.summary()));
  }
  public void showWeights(String key) {
    Log.info("[main] " + key + " wHatch=" + wHatch.summary());
    Log.info("[main] " + key + " wSquash=" + (wSquash == null ? "null" : wSquash.summary()));
    Log.info("[main] " + key + " wGlobal=" + (wGlobal == null ? "null" : wGlobal.summary()));
  }

  public void makeWeightUnitLength() {
    if (wHatch != null) wHatch.makeWeightsUnitLength();
    if (wSquash != null) wSquash.makeWeightsUnitLength();
    if (wGlobal != null) wGlobal.makeWeightsUnitLength();
  }

  @Override
  public boolean isLeaf(Node2 n) {
    boolean b1 = super.isLeaf(n);
    boolean b2 = n.getType() == TFKS.S;
    assert b1 == b2;
    return b1;
  }

  public void setCachedFeatures(CachedFeatures cf) {
    setCachedFeatures(cf.params);
  }
  public void setCachedFeatures(CFLike cf) {
    if (MAIN_LOGGING)
      Log.info("[main] setting CachedFeatures: " + cf);
    this.cachedFeatures = cf;
  }

  public void flushAlphabet() {
    if (MAIN_LOGGING)
      Log.info("[main] flushing alphabet");
    alph = new Alphabet<>();
  }

  public void zeroOutWeights(boolean includeWeightSums) {
    if (MAIN_LOGGING)
      Log.info("[main] zeroing weights, includeWeightSums=" + includeWeightSums);
    if (wHatch != null) wHatch.zeroWeights();
    if (wSquash != null) wSquash.zeroWeights();
    if (wGlobal != null) wGlobal.zeroWeights();
    if (includeWeightSums) {
      if (wHatch != null) wHatch.zeroWeightsAverage();
      if (wSquash != null) wSquash.zeroWeightsAverage();
      if (wGlobal != null) wGlobal.zeroWeightsAverage();
    }
  }

  @Override
//  public boolean oneAtATime(int type) {
  public int oneAtATime() {
    return oneAtATime;
//    if (type <= oneAtATime) {
//      COUNTER_MISC++;
//      return true;
//    }
//    return super.oneAtATime(type);
  }

  @Override
  public int preterminalType() {
    return TFKS.K;
  }

  @Override
  public int hatchesPer(int type) {
    if (type == TFKS.K)
      return hatchesPerK;
    else
      return 1;
  }

  /**
   * Primes are assigned first-come-first-serve so that we get small primes and
   * small primes products for most inference tasks. This class keeps an alphabet
   * of prefix:LL<TV> which ensures this first-come-first-server property.
   * Since this object will live longer than one single inference task, this
   * method should be called in-between in order to re-set the alphabet so it
   * does not grow indefinitely.
   *
   * This should not affect correctness, only performance.
   *
   * TODO Actually now that I think about it, not calling this probably
   * constitutes a memory leak since the prefix:LL<TV> keys will build up over
   * the lifetime of this transition system.
   */
  public void flushPrimes() {
    if (!LLSSP.DISABLE_PRIMES) {
      synchronized (this) {
        prefix2primeIdx = new Alphabet<>();
      }
    }
  }

  public long primeFor(TFKS prefix) {
    if (LLSSP.DISABLE_PRIMES)
      return 0;
    synchronized (prefix2primeIdx) {
      int i = prefix2primeIdx.lookupIndex(prefix, true);
      return primes.get(i);
    }
  }

  @Override
  public TFKS consPrefix(TVNS car, TFKS cdr, Info info) {
    return new TFKS(car, cdr);
  }

  @Override
  public LLSSP consChild(Node2 car, LLSSP cdr, Info info) {
    if (useGlobalFeats && car.getType() == TFKS.K)
      return new LLSSPatF(car, (LLSSPatF) cdr, info, wGlobal);
    return new LLSSP(car, cdr);
  }

  @Override
  public LLTVN consEggs(TVN car, LLTVN cdr, Info info) {
    return new LLTVN(car, cdr);
  }

  @Override
  public LLTVNS consPruned(TVNS car, LLTVNS cdr, Info info) {
    return new LLTVNS(car, cdr);
  }

  @Override
  public Node2 newNode(TFKS prefix, LLTVN eggs, LLTVNS pruned, LLSSP children) {
    return new Node2(prefix, eggs, pruned, children);
  }

  private long primeFor(int type, int value) {
    if (LLSSP.DISABLE_PRIMES)
      return 0;
    TVNS tvns = new TVNS(type, value, -1, -1, 2, null, Double.NaN);
    TFKS tfks = new TFKS(tvns, null);
    return primeFor(tfks);
  }
  private TVN tvnFor(int type, int value) {
    return new TVN(type, value, 1, 1, primeFor(type, value));
  }
  private LL<TVN> encSug(int t, int f, int k, int s) {
    Info info = null; // I believe this is only used for constructing a LabelIndex/Info, so we don't need to depend on Info
    return consEggs(tvnFor(TFKS.S, s),
        consEggs(tvnFor(TFKS.K, k),
            consEggs(tvnFor(TFKS.F, f),
                consEggs(tvnFor(TFKS.T, t), null, info), info), info), info);
  }

  @Override
  public Iterable<LL<TVN>> encode(FNParse y) {

    if (AbstractTransitionScheme.DEBUG && DEBUG_ENCODE)
      Log.info("encoding y=" + Describe.fnParse(y));
    tstart("encode");
    List<LL<TVN>> yy = new ArrayList<>();
    int n = y.getSentence().size();
    for (FrameInstance fi : y.getFrameInstances()) {
      Frame fr = fi.getFrame();
      int f = fr.getId();
      int t = Span.encodeSpan(fi.getTarget(), n);
      int K = fr.numRoles();
      int before = yy.size();
      for (int k = 0; k < K; k++) {
        Span a = fi.getArgument(k);
        if (a != Span.nullSpan) {
          int s = Span.encodeSpan(a, n);
          yy.add(encSug(t, f, k+0*K, s));
        }
        for (Span ca : fi.getContinuationRoleSpans(k)) {
          int s = Span.encodeSpan(ca, n);
          yy.add(encSug(t, f, k+1*K, s));
        }
        for (Span ra : fi.getReferenceRoleSpans(k)) {
          int s = Span.encodeSpan(ra, n);
          yy.add(encSug(t, f, k+2*K, s));
        }
      }
      if (yy.size() == before) {
        // We have not added any roles. This is a problem because it means that
        // there are no smaller prefixes which support the positive (t,f) nodes
        // which should appear.
        // Deomonstrative bug, use FMode.main with EARLY update on FNFUTXT1271864
        Info info = null; // I believe this is only used for constructing a LabelIndex/Info, so we don't need to depend on Info
        yy.add(consEggs(tvnFor(TFKS.F, f),
            consEggs(tvnFor(TFKS.T, t), null, info), info));
      }
    }
    if (AbstractTransitionScheme.DEBUG && DEBUG_ENCODE) {
      Log.info("yy.size=" + yy.size());
    }
    tstop("encode");
    return yy;
  }

  // NOTE: Needs to match genEggs
  public static String roleName(int k, Frame f) {
    assert k >= 0;
    int K = f.numRoles();
    int q = k / K;
    String r = f.getRole(k % K);
    if (q == 1)
      r = "C-" + r;
    else if (q == 2)
      r = "R-" + r;
    else
      assert q == 0;
    return r;
  }

  public Map<FrameInstance, List<Pair<String, Span>>> groupByFrame(
      Iterable<LL<TVNS>> z,
      Info info) {
    Sentence sent = info.getSentence();
    int sentLen = sent.size();
    if (AbstractTransitionScheme.DEBUG && DEBUG_DECODE)
      Log.info("z=" + z);
    FrameIndex fi = info.getFrameIndex();
    Map<FrameInstance, List<Pair<String, Span>>> m = new HashMap<>();
    for (LL<TVNS> prefix : z) {
      TFKS p = (TFKS) prefix;
      if (p == null) {
        if (AbstractTransitionScheme.DEBUG && DEBUG_DECODE)
          Log.info("skipping b/c null");
        continue;
      }
      if (!p.isFull()) {
        if (AbstractTransitionScheme.DEBUG && DEBUG_DECODE)
          Log.info("skipping not full: " + p);
        continue;
      }
      Span t = Span.decodeSpan(p.t, sentLen);
      Frame f = fi.getFrame(p.f);
      FrameInstance key = FrameInstance.frameMention(f, t, sent);
      List<Pair<String, Span>> args = m.get(key);
      if (args == null) {
        args = new ArrayList<>();
        m.put(key, args);
      }
      if (!useContRoles && !useRefRoles)
        assert p.k < f.numRoles();
      String k = roleName(p.k, f);
      Span s = Span.decodeSpan(p.s, sentLen);
      args.add(new Pair<>(k, s));
      if (AbstractTransitionScheme.DEBUG && DEBUG_DECODE)
        Log.info("adding t=" + t.shortString() + " f=" + f.getName() + " k=" + k + " s=" + s.shortString());
    }
    return m;
  }

  @Override
  public FNParse decode(Iterable<LL<TVNS>> z, Info info) {
    if (AbstractTransitionScheme.DEBUG && DEBUG_DECODE)
      Log.info("starting...");
    Sentence sent = info.getSentence();
    List<FrameInstance> fis = new ArrayList<>();
    Set<FrameInstance> addedTF = new HashSet<>();
    for (Map.Entry<FrameInstance, List<Pair<String, Span>>> tf2ks : groupByFrame(z, info).entrySet()) {
      Span t = tf2ks.getKey().getTarget();
      Frame f = tf2ks.getKey().getFrame();
      if (AbstractTransitionScheme.DEBUG && DEBUG_DECODE)
        Log.info("t=" + t.shortString() + " f=" + f.getName());
      try {
        fis.add(FrameInstance.buildPropbankFrameInstance(f, t, tf2ks.getValue(), sent));
        addedTF.add(FrameInstance.frameMention(f, t, sent));
      } catch (Exception e) {
        Log.info("t=" + t.shortString() + " f=" + f.getName());
        for (Pair<String, Span> x : tf2ks.getValue())
          Log.info(x);
        throw new RuntimeException(e);
      }
    }

    // Method above only adds (t,f) for which we've labeled at least one arg,
    // but we're assuming we have all (t,f) even if we don't.
    for (FrameInstance fi : info.getLabelParse().getFrameInstances()) {
      FrameInstance m = FrameInstance.frameMention(fi.getFrame(), fi.getTarget(), fi.getSentence());
      if (!addedTF.contains(m))
        fis.add(m);
    }

    // The CoNLL 2005 srl-eval.pl doesn't like it if your columns (predicates)
    // are out of order.
    Collections.sort(fis, FrameInstance.BY_SENTENCE_POSITION_ASC);

    return new FNParse(sent, fis);
  }

  /**
   * Give the size of the tree rooted at (type,value), whose parents are given
   * by prefix.
   */
  private int subtreeSize(int type, int value, TFKS prefix, Info info) {
    assert (type == 0 && prefix == null) || (type > 0 && prefix.dbgGetValue(type - 1) >= 0)
      : "type=" + type + " prefix=" + prefix;
    int sentLen = info.getSentence().size();
    int n = 0;
    if (type == TFKS.T) {
      if (Node2.INTERNAL_NODES_COUNT)
        n++;  // Count the T node
      // Add in children (TF nodes)
      Span ts = Span.decodeSpan(value, sentLen);
      for (Frame f : info.getPossibleFrames(ts)) {
        int K = f.numRoles();
        if (info.getConfig().useContRoles)
          K += f.numRoles();
        if (info.getConfig().useRefRoles)
          K += f.numRoles();
        int S = info.getPossibleArgs(f, ts).size();
        if (Node2.INTERNAL_NODES_COUNT)
          n += 1 + K * (1 + S);
        else
          n += K * S;
      }
    } else if (type == TFKS.F) {
      Span ts = Span.decodeSpan(prefix.t, sentLen);
      Frame f = info.getFrameIndex().getFrame(value);
      int K = f.numRoles();
      if (info.getConfig().useContRoles)
        K += f.numRoles();
      if (info.getConfig().useRefRoles)
        K += f.numRoles();
      int S = info.getPossibleArgs(f, ts).size();
      if (Node2.INTERNAL_NODES_COUNT) {
        n++;  // Count the TF node
        n += K * (1 + S); // K children, each of which must be counted on its own (inner +1) and has S children
      } else {
        n += K * S;
      }
    } else if (type == TFKS.K) {
      Span ts = Span.decodeSpan(prefix.t, sentLen);
      Frame f = info.getFrameIndex().getFrame(prefix.f);
      int S = info.getPossibleArgs(f, ts).size();
      if (Node2.INTERNAL_NODES_COUNT)
        n++;  // Count the TFK node
      n += S; // There are S children which are leaf nodes
    } else if (type == TFKS.S) {
      n += 1;
    } else {
      throw new RuntimeException("unknown type: " + type);
    }
    return n;
  }

  @Override
  public LLTVN genEggs(TFKS momPrefix, Info info) {
    if (AbstractTransitionScheme.DEBUG_KIBASH && AbstractTransitionScheme.DEBUG && DEBUG_GEN_EGGS)
      Log.info("prefix=" + momPrefix);
    int momType = momPrefix == null ? -1 : momPrefix.car().type;
    if (momType == TFKS.S)
      return null;
    final int sentLen = info.getSentence().size();

    /*
     * Though we only need to produce TVNs for eggs, there is nothing that says
     * we can't use TVNS for eggs. genEggs produces TVNS with static scores and
     * then sorts them accordingly. scoreHatch normally takes egg:TVN -> child:TVNS,
     * but we can have a special case which looks for egg:TVNS(staticFeats) and
     * only adds in dynamic features.
     */

    if (useContRoles || useRefRoles) {
      /*
       * NOTE: However K valued eggs are sorted, every base role must come before
       * its continuation or reference version. Additionally, the FN counts for
       * base roles must account for their cont/ref extensions.
       *
       * For a minute: genEggs(S) could generate eggs for cont/ref roles.
       * S -> (Cont|Ref) -> S'
       * S -> Q -> R
       * TFKS => TFKS(QR)
       *
       * This makes computing loss conceptually easier...
       * Struggling to find a reason not to do it this way. I think this clearly
       * is a better way to do it, but perhaps the only argument to be had is
       * whether C/R roles should be predicted at all.
       */
      throw new RuntimeException("consider how to implement C/R roles...");
    }

    // Get or generate (k,s) eggs sorted according to a static score.
    if (momPrefix != null &&
        (momPrefix.car().type == TFKS.F
        || momPrefix.car().type == TFKS.K)
        && sortEggsMode != SortEggsMode.NONE) {
      if (AbstractTransitionScheme.DEBUG_KIBASH && AbstractTransitionScheme.DEBUG && DEBUG_FEATURES)
        Log.info("using SortedEggCache to pre-compute static scores for eggs and sort them");

      // TODO Right now we compute all K and S valued eggs, refactor so that
      // K eggs are computed separately from S eggs. Originally did this because
      // score(K egg) = max_S score(S egg), but its not clear that I should be
      // doing this anymore (and SortedEggCache didn't even implement this, it
      // used the static feature on K valued eggs, which was not a max).

      // Generate all (k,s) possibilities given the known (t,f)
      SortedEggCache eggs = info.getSortedEggs(momPrefix.t, momPrefix.f);
      if (eggs != null) {
//        System.out.println("who won this race?");
      } else {
        COUNTER_GEN_EGGS_CACHE_MISS++;
        List<Pair<TFKS, EggWithStaticScore>> egF = new ArrayList<>();
        List<Pair<TFKS, EggWithStaticScore>> egK = new ArrayList<>();

        Frame f = getFrame(momPrefix, info);
        Span t = getTarget(momPrefix, info);
        List<Span> possArgSpans = info.getPossibleArgs(f, t);
        if (possArgSpans.size() == 0)
          throw new RuntimeException("check this!");
        assert !possArgSpans.contains(Span.nullSpan);
        if (useContRoles || useRefRoles)
          throw new RuntimeException("implement me");
        for (int k = f.numRoles() - 1; k >= 0; k--) {

          int possK = subtreeSize(TFKS.K, k, momPrefix, info);
          int goldK = info.getLabel().getCounts2(TFKS.K, k, momPrefix);
          long primeK = primeFor(TFKS.K, k);

          TFKS prefixK = momPrefix.dumbPrepend(TFKS.K, k);
          Adjoints modelK = staticFeats0(
              prefixK, info, wHatch);
          double randK = info.getRand(prefixK);
          EggWithStaticScore ef = new EggWithStaticScore(
              TFKS.K, k, possK, goldK, primeK, modelK, randK);
          egF.add(new Pair<>(prefixK, ef));

          for (Span argSpan : possArgSpans) {
            int s = Span.encodeSpan(argSpan, sentLen);
            int possS = subtreeSize(TFKS.S, s, prefixK, info);
            int goldS = info.getLabel().getCounts2(TFKS.S, s, prefixK);
            long primeS = primeFor(TFKS.S, s);

            /*
             * Sort eggs by hatch score?
             * Sort eggs by hatch-squash meta score?
             * Lets try hatch first, and if I decide to change it later, I at least have the start of that impl
             *
             * NOTE: In scoreHatch, I need to catch these features!
             */
            TFKS prefixS = prefixK.dumbPrepend(TFKS.S, s);
            double randS = info.getRand(prefixS);
            Adjoints staticScore = staticFeats0(prefixS, info, wHatch);
            EggWithStaticScore es = new EggWithStaticScore(
                TFKS.S, s, possS, goldS, primeS, staticScore, randS);
            egK.add(new Pair<>(prefixS, es));
          }
        }
        if (sortEggsMode == SortEggsMode.BY_MODEL_SCORE) {
          IntFunction<Span> decodeSpan = s -> Span.decodeSpan(s, sentLen);
          eggs = new SortedEggCache(egF, egK, sortEggsKmaxS, info.htsBeam, decodeSpan);
        } else if (sortEggsMode == SortEggsMode.BY_EXPECTED_UTILITY) {
          IntFunction<Span> decodeSpan = s -> Span.decodeSpan(s, sentLen);
          eggs = new ExpectedUtilityEggSorter.Adapter(egF, egK, sortEggsKmaxS, info.htsBeam, decodeSpan);
        } else {
          throw new RuntimeException("unknown mode: " + sortEggsMode);
        }
        info.putSortedEggs(momPrefix.t, momPrefix.f, eggs);
      }
      if (momType == TFKS.F) {
        return eggs.getSortedEggs();
      }
      if (momType == TFKS.K) {
        return eggs.getSortedEggs(momPrefix.k);
      }
      throw new RuntimeException("wat: momType=" + momType);
    }

    // BY NOW we're assuming that eggs don't have a static score/ordering
    // (at least for the node types that would be affected by this).

    // () -> T*
    LLTVN l = null;
    if (momPrefix == null) {
      for (Span ts : info.getPossibleTargets()) {
        int t = Span.encodeSpan(ts, sentLen);
        int poss = subtreeSize(TFKS.T, t, momPrefix, info);
        int c = info.getLabel().getCounts2(TFKS.T, t, momPrefix);
        long prime = primeFor(TFKS.T, t);
        if (AbstractTransitionScheme.DEBUG && DEBUG_GEN_EGGS)
          Log.info("T poss=" + poss + " c=" + c);
        l = consEggs(new TVN(TFKS.T, t, poss, c, prime), l, info);
      }
      return l;
    }

    Span t;
    Frame f;
    switch (momType) {
    case TFKS.T:      // T -> F*
      t = Span.decodeSpan(momPrefix.t, sentLen);
      for (Frame ff : info.getPossibleFrames(t)) {
        int poss = subtreeSize(TFKS.F, ff.getId(), momPrefix, info);
        int c = info.getLabel().getCounts2(TFKS.F, ff.getId(), momPrefix);
        long prime = primeFor(TFKS.F, ff.getId());
        if (AbstractTransitionScheme.DEBUG && DEBUG_GEN_EGGS)
          Log.info("F poss=" + poss + " c=" + c);
        l = consEggs(new TVN(TFKS.F, ff.getId(), poss, c, prime), l, info);
      }
      return l;
    case TFKS.F:      // TF -> K*
      f = getFrame(momPrefix, info);
      for (int k = f.numRoles() - 1; k >= 0; k--) {
        int poss = subtreeSize(TFKS.K, k, momPrefix, info);
        int c = info.getLabel().getCounts2(TFKS.K, k, momPrefix);
        long prime = primeFor(TFKS.K, k);
        if (AbstractTransitionScheme.DEBUG && DEBUG_GEN_EGGS)
          Log.info("K poss=" + poss + " c=" + c);
        l = consEggs(new TVN(TFKS.K, k, poss, c, prime), l, info);
      }
      if (useContRoles)
        throw new RuntimeException("implement me");
      if (useRefRoles)
        throw new RuntimeException("implement me");
      return l;
    case TFKS.K:        // TFK -> S*
      t = getTarget(momPrefix, info);
      f = getFrame(momPrefix, info);
      List<Span> poss = info.getPossibleArgs(f, t);
      if (poss.size() == 0)
        throw new RuntimeException("check this!");
      assert !poss.contains(Span.nullSpan);
      for (Span ss : poss) {
        int s = Span.encodeSpan(ss, sentLen);
        int possC = subtreeSize(TFKS.S, s, momPrefix, info);
        int c = info.getLabel().getCounts2(TFKS.S, s, momPrefix);
        long prime = primeFor(TFKS.S, s);
        if (AbstractTransitionScheme.DEBUG && DEBUG_GEN_EGGS)
          Log.info("K poss=" + possC + " c=" + c);
        l = consEggs(new TVN(TFKS.S, s, possC, c, prime), l, info);
      }
      return l;
    default:
      throw new RuntimeException();
    }
  }

  /* Convenience methods for getting FN-specific values out of this type ******/
  // In general, these must match genEggs, which is why they're here.
  public static Span getTarget(TFKS t, Info info) {
    assert t.t >= 0;
    int sentenceLen = info.getSentence().size();
    return Span.decodeSpan(t.t, sentenceLen);
  }
  public static Frame getFrame(TFKS t, Info info) {
    assert t.f >= 0;
    FrameIndex fi = info.getFrameIndex();
    return fi.getFrame(t.f);
  }
  /**
   * Returns (k,q) where k is in [0,frame.numRoles) and q is the ordinal for
   * some {@link RoleType}.
   *
   * TODO consider other approach: rather than flatten (k,q) -> k', have more
   * levels in the hierarchy for a C/R role and span (see note in genEggs).
   */
  public static IntPair getRole(TFKS t, Info info) {
    assert t.k >= 0;
    Frame f = getFrame(t, info);
    int K = f.numRoles();
    int k = t.k % K;
    int q = t.k / K;
    assert q < RoleType.values().length;
    return new IntPair(k, q);
  }
  public int numRoles(TFKS t, Info info) {
    if (useContRoles || useRefRoles)
      throw new RuntimeException("fixme");
    Frame f = getFrame(t, info);
    return f.numRoles();
  }
  public static Span getArgSpan(TFKS t, Info info) {
    assert t.s >= 0;
    int sentenceLen = info.getSentence().size();
    return Span.decodeSpan(t.s, sentenceLen);
  }
  /* **************************************************************************/

  private List<String> dynFeats1(Node2 n, Info info) {
    assert n.eggs != null : "hatch/squash both require an egg!";
    TVN egg = n.eggs.car();
    /*
     * TODO Optimize this.
     * scoreHatch/Squash needs to return a TVNS to append to the prejfix. This
     * TVNS will have the score for the respective hatch/squash. That TVNS is
     * created in scoreHatch/Squash (above this). That TVNS should only be
     * created once. This instance is not necessary (makes the code a little
     * clearer). I can't create the TVNS and pass a pointer to the Adjoints to
     * modify here since Adjoints are immutable (maybe make a special purpose
     * one for this reason though).
     * Short term: keep as is
     * Longer term: either refactor this code to never need a TFKS/TVNS or do
     * the *Adjoints trick mentioned above.
     */
    TVNS removeMe = egg.withScore(Adjoints.Constant.ZERO, 0);
    TFKS p = consPrefix(removeMe, n.prefix, info);
    List<String> feats = new ArrayList<>();
    if (featOverfit) {
      feats.add(p.str());
    } else {
      feats.add("intercept_" + egg.type);
      Frame f;
      switch (egg.type) {
      case TFKS.S:
        assert p.t >= 0;
        assert p.f >= 0;
        assert p.k >= 0;
        f = info.getFrameIndex().getFrame(p.f);
        feats.add("K * f=" + f.getName());
        String role = f.getRole(p.k);
        feats.add("K * f=" + f.getName() + " * k=" + role);
        break;
      case TFKS.K:
        assert p.f >= 0;
        f = info.getFrameIndex().getFrame(p.f);
        feats.add("F * f=" + f.getName());
        break;
      }
    }
    return feats;
  }

  private List<ProductIndex> dynFeats2(Node2 n, Info info, List<ProductIndex> addTo, int dimension) {
    for (String fs : dynFeats1(n, info)) {
      int i = alph.lookupIndex(fs, true);
      if (AbstractTransitionScheme.DEBUG_KIBASH && AbstractTransitionScheme.DEBUG && DEBUG_FEATURES) {
        Log.info("wHatch[this]=" + wHatch.getWeight(i)
          + " wSquash[this]=" + (onlyUseHatchWeights ? -wHatch.getWeight(i) : wSquash.getWeight(i))
          + " fs=" + fs);
      }
      addTo.add(PI_DYN.prod(i, dimension));
    }
    return addTo;
  }

  @SuppressWarnings("unused")
  private ProductIndexAdjoints dynFeats0(Node2 n, Info info, WeightsInfo weights) {
    List<ProductIndex> feats = dynFeats2(n, info, new ArrayList<>(), weights.dimension());
    boolean attemptApplyL2Update = false;   // done in Update instead!
    ProductIndexAdjoints a = new ProductIndexAdjoints(weights, feats, attemptApplyL2Update);
    return a;
  }

//  private List<ProductIndex> staticFeats1Compute(TVN egg, TFKS motherPrefix, Info info, List<ProductIndex> addTo, int dimension) {
  private List<ProductIndex> staticFeats1Compute(TFKS eggPrefix, Info info, List<ProductIndex> addTo, int dimension) {
    // TODO Same refactoring as in dynFeats1
    if (featOverfit) {
      //      TVNS removeMe = egg.withScore(Adjoints.Constant.ZERO, 0);
      //      TFKS prefix = consPrefix(removeMe, motherPrefix, info);
      //      String fs = prefix.str();
      //      int i = alph.lookupIndex(fs, true);
      //      if (AbstractTransitionScheme.DEBUG && DEBUG_FEATURES) {
      //        Log.info("wHatch[this]=" + wHatch.getWeight(i)
      //          + " wSquash[this]=" + (onlyUseHatchWeights ? -wHatch.getWeight(i) : wSquash.getWeight(i))
      //          + " fs=" + fs);
      //      }
      //      addTo.add(PI_STAT_S.destructiveProd(i)); // destroys card, can't prod in any more features
      throw new RuntimeException("re-implement me");
    }

    final int eggType = eggPrefix.car().type;

    // What to do if this is a T-valued or F-valued egg?
    // The only eggs which will be present are positive examples, so one indicator feature should be sufficient
    //      if (motherPrefix == null || motherPrefix.car().type == TFKS.T) {
    if (eggType == TFKS.F) {
      // I'm ok with this possible collision...
      addTo.add(PI_STAT_T.prod(42, dimension));
      return addTo;
    }

    //      if (motherPrefix.car().type == TFKS.F) {
    if (eggType == TFKS.K) {
      // Simple features for k-valued children
      //        int k = egg.value;
      int k = eggPrefix.car().value;
      //        ProductIndex fk = PI_STAT_K
      //            .prod(motherPrefix.f, info.numFrames())
      //            .prod(k, numRoles(motherPrefix, info));
      //        addTo.add(fk);
      // F features don't discriminate K values
      // K features may not generalize well, don't have time to tune this.
      // Stick with just a FK feature here.
      FrameRolePacking frp = info.getFRPacking();
      int fk = frp.index(getFrame(eggPrefix, info), k);
      int FK = frp.size();
      addTo.add(PI_STAT_K.prod(fk, FK));
      return addTo;
    }

    //      if (motherPrefix.car().type == TFKS.K) {
    if (eggType == TFKS.S) {
      // Rich (static) features for s-valued children
      assert cachedFeatures != null : "forget to set CachedFeatures?";
      Sentence sent = info.getSentence();
      int sentLen = sent.size();
      Span t = Span.decodeSpan(eggPrefix.t, sentLen);
      Span s = Span.decodeSpan(eggPrefix.s, sentLen);
      FrameIndex fi = info.getFrameIndex();
      Frame frame = fi.getFrame(eggPrefix.f);

      // These are exact feature indices (from disk, who precompute pipeline)
      COUNTER_COMPUTE_FEATS++;
      List<ProductIndex> feats = cachedFeatures.getFeaturesNoModulo(sent, t, s);

      if (featProdBase) {
        for (ProductIndex pi : feats) {
          long i = pi.getProdFeature();
          addTo.add(PI_STAT_S.destructiveProd(i));
        }
      }

      if (featProdF) {
        int f = frame.getId();
        int F = fi.getNumFrames();
        ProductIndex p = PI_STAT_S_F.prod(f, F);
        for (ProductIndex pi : feats) {
          long i = pi.getProdFeature();
          addTo.add(p.destructiveProd(i));
        }
      }

      if (featProdFK) {
        FrameRolePacking frp = info.getFRPacking();
        int fk = frp.index(frame, eggPrefix.k);
        int FK = frp.size();
        ProductIndex p = PI_STAT_S_FK.prod(fk, FK);
        for (ProductIndex pi : feats) {
          long i = pi.getProdFeature();
          addTo.add(p.destructiveProd(i));
        }
      }

      if (featProdK) {
        //          int k = motherPrefix.k;
        //          int K = frame.numRoles();
        RolePacking rp = info.getRPacking();
        int k = rp.getRole(frame, eggPrefix.k);
        int K = rp.size();
        ProductIndex p = PI_STAT_S_K.prod(k, K);
        for (ProductIndex pi : feats) {
          long i = pi.getProdFeature();
          addTo.add(p.destructiveProd(i));
        }
      }

      return addTo;
    }

    throw new RuntimeException("forget a case? " + eggPrefix);
  }
//  public Adjoints staticFeats0(Node2 n, Info info, ProductIndexWeights weights) {
//    assert n.eggs != null : "hatch/squash both require an egg!";
//    TVN egg = n.eggs.car();
//    return staticFeats0(egg, n.prefix, info, weights);
//  }
//  public Adjoints staticFeats0(TVN egg, TFKS motherPrefix, Info info, ProductIndexWeights weights) {
  public Adjoints staticFeats0(TFKS eggPrefix, Info info, ProductIndexWeights weights) {
    assert (weights == wHatch) != (weights == wSquash);
    boolean flip = false;
    if (onlyUseHatchWeights && weights != wHatch) {
      flip = true;
      weights = wHatch;
    }

    // This should be memoized because there is still a loop over all the features in ProductIndexAdjoints.init
    Map<TFKS, Caching> m;
    if (onlyUseHatchWeights) {
      m = info.staticHatchFeatCache;
    } else {
      m = weights == wHatch ? info.staticHatchFeatCache : info.staticSquashFeatCache;
    }
    TFKS memoKey = eggPrefix; //motherPrefix.dumbPrepend(egg);
    Caching pia = m.get(memoKey);
    if (pia == null) {
//      List<ProductIndex> feats = staticFeats1Compute(egg, motherPrefix, info, new ArrayList<>(), weights.dimension());
      List<ProductIndex> feats = staticFeats1Compute(eggPrefix, info, new ArrayList<>(), weights.dimension());
      boolean convertList2Array = true;
      pia = new Caching(weights.score(feats, convertList2Array));
      m.put(memoKey, pia);
    }

    // This way if you compute the dot product for hatch, you've also computed
    // it for squash!
    if (flip) {
      if (addConstantToSquashParams) {
        // Setup for score(squash) = intercept - score(hatch)
        // I seemed to be having problems in bias towards precision when I used:
        //   score(squash) = -score(hatch)
        Adjoints interceptA = wHatch.intercept(0);
        return new Adjoints.CachingBinarySum(interceptA, new Adjoints.Scale(-1, pia));
      } else {
        return new Adjoints.Scale(-1, pia);
      }
    }
    return pia;
  }

  /**
   * Returns true if the next hatch/squash action from the given node is not
   * allowed because the requisite base role has not been realized. If this is
   * an F node (with K valued eggs), then this returns false.
   */
  private boolean unlicensedContOrRefRole(Node2 n, Info info) {
    if (!useContRoles && !useRefRoles)
      return false;
    if (n.getType() == TFKS.F) {
      TFKS prefixWithK = n.prefix.dumbPrepend(n.eggs.car());
      IntPair kq = getRole(prefixWithK, info);
      if (kq.second != RoleType.BASE.ordinal()) {
        LLSSPatF kids = (LLSSPatF) n.children;
        if (!kids.realizedRole(kq.first))
          return true;
      }
    }
    return false;
  }

  @Override
  public TVNS scoreHatch2(Node2 n, TVN egg, Info info) {

    if (n.getType() <= TFKS.F) {
      // These are always right, since squash is disallowed, score of hatch is irrelevant
      return egg.withScore(Adjoints.Constant.ZERO, 0);
    }

    if (forceAllKHatches && n.getType() == TFKS.F) {
      // scoreSquash always returns null (-infinity), so 0 is fine
      return egg.withScore(Adjoints.Constant.ZERO, 0);
    }

    if (unlicensedContOrRefRole(n, info)) {
      // Don't allow this to be hatched
      return null;
    }

    double r = info.getConfig().recallBias;

    // Static score
    if (egg instanceof EggWithStaticScore) {
      // Recover the static features which were computed in genEggs
      if (AbstractTransitionScheme.DEBUG_KIBASH && AbstractTransitionScheme.DEBUG && DEBUG_FEATURES)
        Log.info("recovering static features from EggWithStaticScore");
      EggWithStaticScore eggSS = (EggWithStaticScore) egg;
      if (r != 0)
        eggSS.setModel(Adjoints.cacheSum(eggSS.getModel(), new Adjoints.Constant(r)));
      return eggSS;
    } else {
      // Compute static features for the first time
      if (AbstractTransitionScheme.DEBUG_KIBASH && AbstractTransitionScheme.DEBUG && DEBUG_FEATURES)
        Log.info("computing static features for the first time");
      TFKS eggPrefix = TFKS.dumbCons(egg, n.prefix);
      Adjoints score = staticFeats0(eggPrefix, info, wHatch);
      return egg.withScore(score, info.getRand(eggPrefix));
    }
  }

  @Override
  public TVNS scoreHatch(Node2 n, Info info) {
    return scoreHatch2(n, n.eggs.car(), info);
  }

  @Override
  public TVNS scoreSquash(Node2 n, Info info) {
    TVN egg = n.eggs.car(); // what we're featurizing squashing

    if (n.getType() <= TFKS.F) {
      // These are always right, disallow squash
      return null;
    }

    if (forceAllKHatches && n.getType() == TFKS.F) {
      return null;
    }

    if (unlicensedContOrRefRole(n, info)) {
      // scoreHatch will ensure that squash is chosen, but we don't want to
      // inflate the score here since it is a deterministic action (having
      // nothing to do with model score).
      return egg.withScore(Adjoints.Constant.ZERO, 0);
    }

    TFKS eggPrefix = TFKS.dumbCons(egg, n.prefix);
    Adjoints score = staticFeats0(eggPrefix, info, wSquash);

    return egg.withScore(score, info.getRand(eggPrefix));
  }


  /** Returns the parse-du-jour for testing */
  public static FNParse dummyParse() {
    List<FNParse> ys = State.getParse();
    Collections.sort(ys, new Comparator<FNParse>() {
      @Override
      public int compare(FNParse o1, FNParse o2) {
        return State.numItems(o1) - State.numItems(o2);
      }
    });
    for (FNParse y : ys) {
      if (y.numFrameInstances() == 0)
        continue;
      if (State.numItems(y) < 2)
        continue;
      return y;
    }
    throw new RuntimeException();
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    config.put("featOverfit", "true");

    DEBUG_GEN_EGGS = true;
    AbstractTransitionScheme.DEBUG = false;
    boolean simple = false;

    FNParse y = dummyParse();
    Log.info(Describe.fnParse(y));

    int numShards = 1;
    FModel m = new FModel(null, DeterministicRolePruning.Mode.XUE_PALMER_HERMANN, numShards);
    FNParseTransitionScheme ts = m.getAverageWeights();

    boolean thinKS = true;  // prune out some wrong answers to make the tree smaller (easier to see)
    Info inf = m.getPredictInfo(y, ts, thinKS);
    State2<Info> s0 = ts.genRootState(inf);
    Log.info("this is the root:");
    s0.getRoot().show(System.out);

    // Make a beam that can keep everything
    List<State2<Info>> bl = new ArrayList<>();
    Beam<State2<Info>> b = new Beam<State2<Info>>() {
      public boolean offer(State2<Info> next) {
        return bl.add(next);
      }
      public Double lowerBound() {
        throw new RuntimeException();
      }
      public State2<Info> pop() {
        throw new RuntimeException();
      }
    };

    // First lets try to watch things happen down the left spine
    if (simple) {
      Log.info("\n\n\n\n\n");
      State2<Info> sLeft = s0;

      ts.nextStates(sLeft, b);
      sLeft = bl.stream().filter(s -> s.dbgString.contains("hatch")).reduce((elem,accum) -> accum).get();
      bl.clear();
      sLeft.getRoot().show(System.out);
      ts.nextStates(sLeft, b);
      sLeft = bl.stream().filter(s -> s.dbgString.contains("hatch")).reduce((elem,accum) -> accum).get();
      bl.clear();
      sLeft.getRoot().show(System.out);
      ts.nextStates(sLeft, b);
      sLeft = bl.stream().filter(s -> s.dbgString.contains("hatch")).reduce((elem,accum) -> accum).get();
      bl.clear();
      sLeft.getRoot().show(System.out);
      ts.nextStates(sLeft, b);
      sLeft = bl.stream().filter(s -> s.dbgString.contains("hatch")).reduce((elem,accum) -> accum).get();
      bl.clear();
      sLeft.getRoot().show(System.out);
      Log.info("\n\n\n\n\n");
      return;
    }


    // If we run hatch as much as possible, we should match numPossible
    Log.info("starting numPossible test");
    bl.clear();
    int steps = 0;
    State2<Info> sF = s0;
    while (true) {
//      Log.info("stepping");
      steps++;
      ts.nextStates(sF, b);
      if (bl.size() == 0)
        break;
      sF = bl.stream().filter(s -> s.dbgString.contains("hatch")).findFirst().get();
      bl.clear();
    }
    Log.info("steps=" + steps);

    Log.info("sF:");
    sF.getRoot().show(System.out);

    Log.info("s0:");
    s0.getRoot().show(System.out);

    int s0poss = s0.getStepScores().getLoss().numPossible;
    int sFposs = sF.getStepScores().getLoss().numPossible;
    assert s0poss == sFposs : "s0poss=" + s0poss + " sFposs=" + sFposs;
    int sFn = Node2.numChildren(sF.getRoot());
    assert s0poss == sFn : "s0poss=" + s0poss + " sFn=" + sFn;
    Log.info("done! everything went well :)");
  }
}

