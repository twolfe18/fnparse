package edu.jhu.hlt.fnparse.rl.full2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.rl.full.Primes;
import edu.jhu.hlt.fnparse.rl.full.State.Info;
import edu.jhu.hlt.fnparse.rl.full.StepScores.MaxLoss;
import edu.jhu.hlt.fnparse.rl.full.weights.ProductIndexAdjoints;
import edu.jhu.hlt.fnparse.rl.full2.Node2.NodeWithSignature;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.map.IntDoubleEntry;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;
import edu.jhu.util.Alphabet;

public class FNParseTransitionScheme extends AbstractTransitionScheme<FNParse, Info> {

  public static boolean DEBUG_ENCODE = true;
  
  
  
  public static class Node2Loss extends Node2 {
    private MaxLoss maxLoss;    // represents union of all children/eggs/pruned
    public Node2Loss(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children, MaxLoss loss) {
      super(prefix, eggs, pruned, children);
      maxLoss = loss;
    }
  }
  Node2 newNode2(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children) {
    // I still want children:LL<Node2> to be a LLTVML (actually a LLNode2ML)
    
    // Maybe this should only really be called with children==null
    // How is newNode used in AbstractTransitionScheme?
    
    // Maybe I just need to derive MaxLoss from prefix HERE instead of having a separate function in AbstractTransitionSystem

  }
  
  
  
  
  

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

  public long primeFor(LL<TV> prefix) {
    TFKS p = (TFKS) prefix;
    int i = prefix2primeIdx.lookupIndex(p, true);
    return primes.get(i);
  }

  @Override
  public LL<TV> consPrefix(TV car, LL<TV> cdr) {
    return new TFKS(car, (TFKS) cdr);
  }

  @Override
  public LL<Node2> consChild(Node2 car, LL<Node2> cdr) {
    if (car.getType() == TFKS.K)
      return new RoleLL(car, cdr, this::primeFor);
    return new PrimesLL(car, cdr, this::primeFor);
  }

  @Override
  public NodeWithSignature newNode(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children) {
    return new NodeWithSignature(prefix, eggs, pruned, children);
  }

  @Override
  public Iterable<LL<TV>> encode(FNParse y) {
    if (DEBUG_ENCODE)
      Log.info("encoding y=" + Describe.fnParse(y));
    List<LL<TV>> yy = new ArrayList<>();
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
          yy.add(Node2Tests.lltvSugar(TFKS.S, s, TFKS.K, k+0*K, TFKS.F, f, TFKS.T, t));
        }
        for (Span ca : fi.getContinuationRoleSpans(k)) {
          int s = Span.encodeSpan(ca, n);
          yy.add(Node2Tests.lltvSugar(TFKS.S, s, TFKS.K, k+1*K, TFKS.F, f, TFKS.T, t));
        }
        for (Span ra : fi.getReferenceRoleSpans(k)) {
          int s = Span.encodeSpan(ra, n);
          yy.add(Node2Tests.lltvSugar(TFKS.S, s, TFKS.K, k+2*K, TFKS.F, f, TFKS.T, t));
        }
      }
    }
    if (DEBUG_ENCODE) {
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
      Iterable<LL<TV>> z,
      Info info) {
    Sentence sent = info.getSentence();
    int sentLen = sent.size();
    if (AbstractTransitionScheme.DEBUG)
      Log.info("z=" + z);
    FrameIndex fi = info.getFrameIndex();
    Map<FrameInstance, List<Pair<String, Span>>> m = new HashMap<>();
    for (LL<TV> prefix : z) {
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
  public FNParse decode(Iterable<LL<TV>> z, Info info) {
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

  @Override
  public LL<TV> genEggs(LL<TV> prefix, Info info) {
    LL<TV> l = null;
    int n = info.getSentence().size();

    // () -> T
    if (prefix == null) {
      // TODO Sort these by static model score
      for (Span ts : info.getPossibleTargets()) {
        int t = Span.encodeSpan(ts, n);
        l = new LL<>(new TV(TFKS.T, t), l);
      }
      return l;
    }

    TFKS p;
    if (prefix instanceof TFKS)
      p = (TFKS) prefix;
    else
      throw new IllegalArgumentException("prefix must be a TFKS");
    Span t;
    Frame f;
    FrameIndex fi;
    TV egg = prefix.car();
    switch (egg.getType()) {
    case TFKS.T:      // T -> F
      t = Span.decodeSpan(p.t, n);
      for (Frame ff : info.getPossibleFrames(t)) {
        l = new LL<>(new TV(TFKS.F, ff.getId()), l);
      }
      return l;
    case TFKS.F:
      fi = info.getFrameIndex();
      f = fi.getFrame(p.f);
      int k = f.numRoles() - 1;
      while (k >= 0) {
        l = new LL<>(new TV(TFKS.K, k), l);
        k--;
      }
      if (useContRoles)
        throw new RuntimeException("implement me");
      if (useRefRoles)
        throw new RuntimeException("implement me");
      return l;
    case TFKS.K:
      fi = info.getFrameIndex();
      t = Span.decodeSpan(p.t, n);
      f = fi.getFrame(p.f);
      List<Span> poss = info.getPossibleArgs(f, t);
      if (poss.size() == 0)
        throw new RuntimeException("check this!");
      assert !poss.contains(Span.nullSpan);
      for (Span ss : poss) {
        int s = Span.encodeSpan(ss, n);
        l = new LL<>(new TV(TFKS.S, s), l);
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
    TV egg = n.eggs.car();
    TFKS p = (TFKS) consPrefix(egg, n.prefix);   // of the child!
    List<String> feats = new ArrayList<>();
    if (useOverfitFeatures) {
      feats.add(p.toString());
    } else {
      feats.add("intercept_" + egg.getType());
      Frame f;
      switch (egg.getType()) {
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
    TFKS prefix = (TFKS) consPrefix(n.eggs.car(), n.prefix);   // of the child!
    if (prefix.isFull()) {
      if (useOverfitFeatures) {
        String fs = prefix.toString();
        int i = alph.lookupIndex(fs, true);
//        if (AbstractTransitionScheme.DEBUG)
//          Log.info("i=" + i + " dimension=" + dimension + " fs=" + fs);
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
  public Adjoints featsHatch(Node2 n, Info info) {
    List<ProductIndex> allFeats = new ArrayList<>();
    dynFeats2(n, info, allFeats);
//    staticFeats1(n, info, allFeats);
    if (AbstractTransitionScheme.DEBUG) {
      Log.info(String.format("wHatch.l2=%.3f weights=%s", wHatch.weights.getL2Norm(), System.identityHashCode(wHatch.weights)));
      for (ProductIndex p : allFeats) {
        int i = p.getProdFeatureModulo(dimension);
        double d = wHatch.weights.get(i);
        Log.info("hatch weight=" + d + " feature=" + p);
      }
    }
    ProductIndexAdjoints pi = new ProductIndexAdjoints(learningRate, l2Reg, dimension, allFeats, wHatch);
    if (AbstractTransitionScheme.DEBUG) {
      pi.nameOfWeights = "hatch";
      pi.showUpdatesWith = alph;
      Log.info("featHatchEarlyAdjoints: " + pi);
    }
    return pi;
  }

  @Override
  public Adjoints featsSquash(Node2 n, Info info) {
    List<ProductIndex> allFeats = new ArrayList<>();
    dynFeats2(n, info, allFeats);
//    staticFeats1(n, info, allFeats);
    if (AbstractTransitionScheme.DEBUG) {
      Log.info(String.format("wSquash.l2=%.3f weights=%s", wSquash.weights.getL2Norm(), System.identityHashCode(wSquash.weights)));
      for (ProductIndex p : allFeats) {
        int i = p.getProdFeatureModulo(dimension);
        double d = wSquash.weights.get(i);
        Log.info("squash weight=" + d + " feature=" + p);
      }
    }
    ProductIndexAdjoints pi = new ProductIndexAdjoints(learningRate, l2Reg, dimension, allFeats, wSquash);
    if (AbstractTransitionScheme.DEBUG) {
      pi.nameOfWeights = "squash";
      pi.showUpdatesWith = alph;
      Log.info("featSquashEarlyAdjoints: " + pi);
    }
    return pi;
  }

}
