package edu.jhu.hlt.fnparse.rl.full2;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.rl.full.Primes;
import edu.jhu.hlt.fnparse.rl.full.State.Info;
import edu.jhu.hlt.fnparse.rl.full.weights.ProductIndexAdjoints;
import edu.jhu.hlt.fnparse.rl.full2.Node2.NodeWithSignature;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.map.IntDoubleEntry;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;
import edu.jhu.util.Alphabet;

public class FNParseTransitionScheme extends AbstractTransitionScheme<FNParse, Info> {

//  private ToLongFunction<LL<TV>> getPrimes;
  private Alphabet<TFKS> prefix2primeIdx;
  private Primes primes;

  public boolean useContRoles = false;
  public boolean useRefRoles = false;

  // Features
  private CachedFeatures cachedFeatures;
  private boolean useOverfitFeatures = false;

  // Weights
  private int dimension = 1 << 20;    // for each weight vector
  private LazyL2UpdateVector wHatch, wSquash;
  private Alphabet<String> alph;
  private double learningRate = 0.01;
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

  public long primeFor(LL<TV> prefix) {
    TFKS p = (TFKS) prefix;
    int i = prefix2primeIdx.lookupIndex(p, true);
    return primes.get(i);
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
    return yy;
  }


  @Override
  public FNParse decode(Iterable<LL<TV>> z) {
    throw new RuntimeException("implement me");
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
      int k = f.numRoles();
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
      for (Span ss : info.getPossibleArgs(f, t)) {
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
    TFKS p = (TFKS) n.prefix;
    List<String> feats = new ArrayList<>();
    TV egg = n.eggs.car();
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
    return feats;
  }

  private void dynFeats2(Node2 n, Info info, List<ProductIndex> addTo) {
    ProductIndex base = ProductIndex.FALSE;
    if (useOverfitFeatures) {
      int i = ((TFKS) n.prefix).hashCode() % dimension;
      addTo.add(base.prod(i, dimension));
    } else {
      for (String fs : dynFeats1(n, info)) {
        int i = alph.lookupIndex(fs, true) % dimension;
        addTo.add(base.prod(i, dimension));
      }
    }
  }

  /** Features from {@link CachedFeatures.Params} */
  private void staticFeats1(Node2 n, Info info, List<ProductIndex> addTo) {
    ProductIndex base = ProductIndex.TRUE;
    TFKS prefix = (TFKS) n.prefix;
    if (prefix != null && prefix.isFull()) {
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
        int i = ide.index() % dimension;
        ProductIndex p = base.prod(i, dimension);
        ProductIndex pp = p.prod(k, K);
        if (roleDepsOnFrame)
          pp = pp.prod(f.getId(), fi.getNumFrames());
        addTo.add(p);
        addTo.add(pp);
      }
    }
  }

  @Override
  public Adjoints featsHatch(Node2 n, Info info) {
    List<ProductIndex> allFeats = new ArrayList<>();
    dynFeats2(n, info, allFeats);
    staticFeats1(n, info, allFeats);
    return new ProductIndexAdjoints(learningRate, l2Reg, dimension, allFeats, wHatch);
//    double r = info.getRandom().nextGaussian();
//    return new Adjoints.NamedConstant("FNTS-hatch-rand", r);
////    throw new RuntimeException("finish this");
  }

  @Override
  public Adjoints featsSquash(Node2 n, Info info) {
    List<ProductIndex> allFeats = new ArrayList<>();
    dynFeats2(n, info, allFeats);
    staticFeats1(n, info, allFeats);
    return new ProductIndexAdjoints(learningRate, l2Reg, dimension, allFeats, wSquash);
//    double r = info.getRandom().nextGaussian();
//    return new Adjoints.NamedConstant("FNTS-squash-rand", r);
////    throw new RuntimeException("finish this");
  }

}
