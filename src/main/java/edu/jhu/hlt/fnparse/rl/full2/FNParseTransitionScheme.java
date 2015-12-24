package edu.jhu.hlt.fnparse.rl.full2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.print.attribute.standard.PrinterMessageFromOperator;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.rl.full.Beam;
import edu.jhu.hlt.fnparse.rl.full.FModel;
import edu.jhu.hlt.fnparse.rl.full.Info;
import edu.jhu.hlt.fnparse.rl.full.Primes;
import edu.jhu.hlt.fnparse.rl.full.SearchCoefficients;
import edu.jhu.hlt.fnparse.rl.full.State;
import edu.jhu.hlt.fnparse.rl.full.weights.ProductIndexAdjoints;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.map.IntDoubleEntry;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;
import edu.jhu.util.Alphabet;

/**
 * An instantiation of {@link AbstractTransitionScheme} which specifies the
 * [targetSpan, frame, role, argSpan] loop order for Propbank/FrameNet prediction.
 *
 * @author travis
 */
public class FNParseTransitionScheme extends AbstractTransitionScheme<FNParse, Info> {

  public static boolean DEBUG_ENCODE = true;
  public static boolean VERBOSE_FEATURES = false;
  public static boolean DEBUG_GEN_EGGS = false;

//  private ToLongFunction<LL<TV>> getPrimes;
  private Alphabet<TFKS> prefix2primeIdx;
  private Primes primes;

  public boolean useContRoles = false;
  public boolean useRefRoles = false;

  // Features
  private CachedFeatures cachedFeatures;
  public boolean useOverfitFeatures = false;

  // Weights
  private int dimension = 1 << 20;    // for each weight vector
  private LazyL2UpdateVector wHatch, wSquash;
  private Alphabet<String> alph;
  private double learningRate = 1;
  private double l2Reg = 1e-7;


  public FNParseTransitionScheme(CachedFeatures cf, Primes primes) {
    this.cachedFeatures = cf;
    this.alph = new Alphabet<>();
    int updateInterval = 32;
    wHatch = new LazyL2UpdateVector(new IntDoubleDenseVector(dimension), updateInterval);
    wSquash = new LazyL2UpdateVector(new IntDoubleDenseVector(dimension), updateInterval);
    prefix2primeIdx = new Alphabet<>();
    this.primes = primes;
  }

  public void setCachedFeatures(CachedFeatures cf) {
    this.cachedFeatures = cf;
  }

  public void flushAlphabet() {
    alph = new Alphabet<>();
  }

  public void zeroWeights() {
    wHatch.weights.scale(0);
    wSquash.weights.scale(0);
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
    prefix2primeIdx = new Alphabet<>();
  }

  public long primeFor(TFKS prefix) {
    int i = prefix2primeIdx.lookupIndex(prefix, true);
    return primes.get(i);
  }

  @Override
  public TFKS consPrefix(TVNS car, TFKS cdr) {
    return new TFKS(car, cdr);
  }

  @Override
  public LLSSP consChild(Node2 car, LLSSP cdr) {
    return new LLSSP(car, cdr);
  }

  @Override
  public LLTVN consEggs(TVN car, LLTVN cdr) {
    return new LLTVN(car, cdr);
  }

  @Override
  public LLTVNS consPruned(TVNS car, LLTVNS cdr) {
    return new LLTVNS(car, cdr);
  }

  @Override
  public Node2 newNode(SearchCoefficients coefs, TFKS prefix, LLTVN eggs, LLTVNS pruned, LLSSP children) {
    return new Node2(coefs, prefix, eggs, pruned, children);
  }

  private long primeFor(int type, int value) {
    TVNS tvns = new TVNS(type, value, -1, -1, 2, null, Double.NaN);
    TFKS tfks = new TFKS(tvns, null);
    return primeFor(tfks);
  }
  private TVN tvnFor(int type, int value) {
    return new TVN(type, value, 1, 1, primeFor(type, value));
  }
  private LL<TVN> encSug(int t, int f, int k, int s) {
    return consEggs(tvnFor(TFKS.S, s),
        consEggs(tvnFor(TFKS.K, k),
            consEggs(tvnFor(TFKS.F, f),
                consEggs(tvnFor(TFKS.T, t), null))));
  }

  @Override
  public Iterable<LL<TVN>> encode(FNParse y) {
    if (AbstractTransitionScheme.DEBUG && DEBUG_ENCODE)
      Log.info("encoding y=" + Describe.fnParse(y));
    List<LL<TVN>> yy = new ArrayList<>();
    int n = y.getSentence().size();
    for (FrameInstance fi : y.getFrameInstances()) {
      Frame fr = fi.getFrame();
      int f = fr.getId();
      int t = Span.encodeSpan(fi.getTarget(), n);
      int K = fr.numRoles();
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
    }
    if (AbstractTransitionScheme.DEBUG && DEBUG_ENCODE) {
      Log.info("yy.size=" + yy.size());
    }
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
    if (AbstractTransitionScheme.DEBUG)
      Log.info("z=" + z);
    FrameIndex fi = info.getFrameIndex();
    Map<FrameInstance, List<Pair<String, Span>>> m = new HashMap<>();
    for (LL<TVNS> prefix : z) {
      TFKS p = (TFKS) prefix;
      if (p == null) {
        if (AbstractTransitionScheme.DEBUG)
          Log.info("skipping b/c null");
        continue;
      }
      if (!p.isFull()) {
        if (AbstractTransitionScheme.DEBUG)
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
      if (AbstractTransitionScheme.DEBUG)
        Log.info("adding t=" + t.shortString() + " f=" + f.getName() + " k=" + k + " s=" + s.shortString());
    }
    return m;
  }

  @Override
  public FNParse decode(Iterable<LL<TVNS>> z, Info info) {
    if (AbstractTransitionScheme.DEBUG)
      Log.info("starting...");
    Sentence sent = info.getSentence();
    List<FrameInstance> fis = new ArrayList<>();
    for (Map.Entry<FrameInstance, List<Pair<String, Span>>> tf2ks : groupByFrame(z, info).entrySet()) {
      Span t = tf2ks.getKey().getTarget();
      Frame f = tf2ks.getKey().getFrame();
      if (AbstractTransitionScheme.DEBUG)
        Log.info("t=" + t.shortString() + " f=" + f.getName());
      try {
        fis.add(FrameInstance.buildPropbankFrameInstance(f, t, tf2ks.getValue(), sent));
      } catch (Exception e) {
        Log.info("t=" + t.shortString() + " f=" + f.getName());
        for (Pair<String, Span> x : tf2ks.getValue())
          Log.info(x);
        throw new RuntimeException(e);
      }
    }
    return new FNParse(sent, fis);
  }

  /**
   * Give the size of the tree rooted at (type,value), whose parents are given
   * by prefix.
   */
  private int subtreeSize(int type, int value, TFKS prefix, Info info) {
    int sentLen = info.getSentence().size();
    int n = 0;
    if (type == TFKS.T) {
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
        n += 1 + K * (1 + S);
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
      n++;  // Count the TF node
      n += K * (1 + S); // K children, each of which must be counted on its own (inner +1) and has S children
    } else if (type == TFKS.K) {
      Span ts = Span.decodeSpan(prefix.t, sentLen);
      Frame f = info.getFrameIndex().getFrame(prefix.f);
      int S = info.getPossibleArgs(f, ts).size();
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
  public LLTVN genEggs(TFKS prefix, Info info) {
    if (AbstractTransitionScheme.DEBUG && DEBUG_GEN_EGGS)
      Log.info("prefix=" + prefix);
    LLTVN l = null;
    int n = info.getSentence().size();

    // () -> T
    if (prefix == null) {
      // TODO Sort these by static model score
      for (Span ts : info.getPossibleTargets()) {
        int t = Span.encodeSpan(ts, n);
        int poss = subtreeSize(TFKS.T, t, prefix, info);
        int c = info.getLabel().getCounts2(TFKS.T, t, prefix);
        long prime = primeFor(TFKS.T, t);
        if (AbstractTransitionScheme.DEBUG && DEBUG_GEN_EGGS)
          Log.info("T poss=" + poss + " c=" + c);
        l = consEggs(new TVN(TFKS.T, t, poss, c, prime), l);
      }
      return l;
    }

    Span t;
    Frame f;
    FrameIndex fi;
    TVN egg = prefix.car();
    switch (egg.type) {
    case TFKS.T:      // T -> F
      t = Span.decodeSpan(prefix.t, n);
      for (Frame ff : info.getPossibleFrames(t)) {
        int poss = subtreeSize(TFKS.F, ff.getId(), prefix, info);
        int c = info.getLabel().getCounts2(TFKS.F, ff.getId(), prefix);
        long prime = primeFor(TFKS.F, ff.getId());
        if (AbstractTransitionScheme.DEBUG && DEBUG_GEN_EGGS)
          Log.info("F poss=" + poss + " c=" + c);
        l = consEggs(new TVN(TFKS.F, ff.getId(), poss, c, prime), l);
      }
      return l;
    case TFKS.F:
      fi = info.getFrameIndex();
      f = fi.getFrame(prefix.f);
      int k = f.numRoles() - 1;
      while (k >= 0) {
        int poss = subtreeSize(TFKS.K, k, prefix, info);
        int c = info.getLabel().getCounts2(TFKS.K, k, prefix);
        long prime = primeFor(TFKS.K, k);
        if (AbstractTransitionScheme.DEBUG && DEBUG_GEN_EGGS)
          Log.info("K poss=" + poss + " c=" + c);
        l = consEggs(new TVN(TFKS.K, k, poss, c, prime), l);
        k--;
      }
      if (useContRoles)
        throw new RuntimeException("implement me");
      if (useRefRoles)
        throw new RuntimeException("implement me");
      return l;
    case TFKS.K:
      fi = info.getFrameIndex();
      t = Span.decodeSpan(prefix.t, n);
      f = fi.getFrame(prefix.f);
      List<Span> poss = info.getPossibleArgs(f, t);
      if (poss.size() == 0)
        throw new RuntimeException("check this!");
      assert !poss.contains(Span.nullSpan);
      for (Span ss : poss) {
        int s = Span.encodeSpan(ss, n);
        int possC = subtreeSize(TFKS.S, s, prefix, info);
        int c = info.getLabel().getCounts2(TFKS.S, s, prefix);
        long prime = primeFor(TFKS.S, s);
        if (AbstractTransitionScheme.DEBUG && DEBUG_GEN_EGGS)
          Log.info("K poss=" + possC + " c=" + c);
        l = consEggs(new TVN(TFKS.S, s, possC, c, prime), l);
      }
      return l;
    case TFKS.S:
      return null;
    default:
      throw new RuntimeException();
    }
  }

  private List<String> dynFeats1(Node2 n, Info info) {
    assert n.eggs != null : "hatch/squash both require an egg!";
    TVN egg = n.eggs.car();
    /*
     * TODO Optimize this.
     * scoreHatch/Squash needs to return a TVNS to append to the prefix. This
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
    TFKS p = consPrefix(removeMe, n.prefix);
    List<String> feats = new ArrayList<>();
    if (useOverfitFeatures) {
      feats.add(p.toString());
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

  private void dynFeats2(Node2 n, Info info, List<ProductIndex> addTo) {
    ProductIndex base = ProductIndex.FALSE;
    for (String fs : dynFeats1(n, info)) {
      int i = alph.lookupIndex(fs, true);
      if (AbstractTransitionScheme.DEBUG)
        Log.info("fs=" + fs + " wHatch[this]=" + wHatch.weights.get(i) + " wSquash[this]=" + wSquash.weights.get(i));
      addTo.add(base.prod(i, dimension));
    }
  }

  /** Features from {@link CachedFeatures.Params} */
  private void staticFeats1(Node2 n, Info info, List<ProductIndex> addTo) {
    assert n.eggs != null : "hatch/squash both require an egg!";
    ProductIndex base = ProductIndex.TRUE;
    // TODO Same refactoring as in dynFeats1
    TVN egg = n.eggs.car();
    TVNS removeMe = egg.withScore(Adjoints.Constant.ZERO, 0);
    TFKS prefix = consPrefix(removeMe, n.prefix);
    if (prefix.isFull()) {
      if (useOverfitFeatures) {
        String fs = prefix.toString();
        int i = alph.lookupIndex(fs, true);
        addTo.add(base.prod(i, dimension));
      } else {
        assert cachedFeatures != null : "forget to set CachedFeatures?";
        Sentence sent = info.getSentence();
        int sentLen = sent.size();
        Span t = Span.decodeSpan(prefix.t, sentLen);
        Span s = Span.decodeSpan(prefix.s, sentLen);
        FrameIndex fi = info.getFrameIndex();
        Frame f = fi.getFrame(prefix.f);
        int k = prefix.k;
        int K = f.numRoles() * 3;
        boolean roleDepsOnFrame = info.getConfig().roleDependsOnFrame;
        IntDoubleUnsortedVector fv = cachedFeatures.params.getFeatures(sent, t, s);
        Iterator<IntDoubleEntry> itr = fv.iterator();
        while (itr.hasNext()) {
          IntDoubleEntry ide = itr.next();
          assert ide.get() > 0; // but you lose magnitude either way
          int i = ide.index();
          ProductIndex p = base.prod(i, dimension);
          ProductIndex pp = p.prod(k, K);
          if (roleDepsOnFrame)
            pp = pp.prod(f.getId(), fi.getNumFrames());
          addTo.add(p);
          addTo.add(pp);
        }
      }
    }
  }

  @Override
  public TVNS scoreHatch(Node2 n, Info info) {
    TVN egg = n.eggs.car(); // what we're featurizing hatching
    List<ProductIndex> allFeats = new ArrayList<>();
    dynFeats2(n, info, allFeats);
    staticFeats1(n, info, allFeats);
    if (AbstractTransitionScheme.DEBUG && VERBOSE_FEATURES) {
      Log.info(String.format("wHatch.l2=%.3f weights=%s", wHatch.weights.getL2Norm(), System.identityHashCode(wHatch.weights)));
      for (ProductIndex p : allFeats) {
        int i = p.getProdFeatureModulo(dimension);
        double d = wHatch.weights.get(i);
        Log.info("hatch weight=" + d + " feature=" + p);
      }
    }
    ProductIndexAdjoints pi = new ProductIndexAdjoints(learningRate, l2Reg, dimension, allFeats, wHatch);
    if (AbstractTransitionScheme.DEBUG && VERBOSE_FEATURES) {
      pi.nameOfWeights = "hatch";
      pi.showUpdatesWith = alph;
      Log.info("featHatchEarlyAdjoints: " + pi);
    }
    return egg.withScore(pi, info.getRandom().nextGaussian());
  }

  @Override
  public TVNS scoreSquash(Node2 n, Info info) {
    TVN egg = n.eggs.car(); // what we're featurizing squashing
    List<ProductIndex> allFeats = new ArrayList<>();
    dynFeats2(n, info, allFeats);
    staticFeats1(n, info, allFeats);
    if (AbstractTransitionScheme.DEBUG && VERBOSE_FEATURES) {
      Log.info(String.format("wSquash.l2=%.3f weights=%s", wSquash.weights.getL2Norm(), System.identityHashCode(wSquash.weights)));
      for (ProductIndex p : allFeats) {
        int i = p.getProdFeatureModulo(dimension);
        double d = wSquash.weights.get(i);
        Log.info("squash weight=" + d + " feature=" + p);
      }
    }
    ProductIndexAdjoints pi = new ProductIndexAdjoints(learningRate, l2Reg, dimension, allFeats, wSquash);
    if (AbstractTransitionScheme.DEBUG && VERBOSE_FEATURES) {
      pi.nameOfWeights = "squash";
      pi.showUpdatesWith = alph;
      Log.info("featSquashEarlyAdjoints: " + pi);
    }
    return egg.withScore(pi, info.getRandom().nextGaussian());
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
    ExperimentProperties.init(args);

    DEBUG_GEN_EGGS = true;
    AbstractTransitionScheme.DEBUG = false;
    boolean simple = false;

    FNParse y = dummyParse();
    Log.info(Describe.fnParse(y));

    FModel m = new FModel(null, DeterministicRolePruning.Mode.XUE_PALMER_HERMANN);
    FNParseTransitionScheme ts = m.getTransitionSystem();
    ts.useOverfitFeatures = true;

    boolean thinKS = true;  // prune out some wrong answers to make the tree smaller (easier to see)
    Info inf = m.getPredictInfo(y, thinKS);
    State2<Info> s0 = ts.genRootState(inf);
    Log.info("this is the root:");
    s0.getRoot().show(System.out);

    // Make a beam that can keep everything
    List<State2<Info>> bl = new ArrayList<>();
    Beam<State2<Info>> b = new Beam<State2<Info>>() {
      public void offer(State2<Info> next) {
        bl.add(next);
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

