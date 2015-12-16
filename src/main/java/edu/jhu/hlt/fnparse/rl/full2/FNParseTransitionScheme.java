package edu.jhu.hlt.fnparse.rl.full2;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.rl.full.State.Info;
import edu.jhu.hlt.fnparse.rl.full2.Node2.NodeWithSignature;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.util.Alphabet;

public class FNParseTransitionScheme extends AbstractTransitionScheme<FNParse> {

  private ToLongFunction<LL<TV>> getPrimes;
  private Info info;
  private int sentenceLen = -1;

  public boolean useContRoles = false;
  public boolean useRefRoles = false;

  // Features
  private CachedFeatures cachedFeatures;
  private boolean useOverfitFeatures = false;

  // Weights
  private LazyL2UpdateVector wHatch, wSquash;
  private Alphabet<String> alph;


  public FNParseTransitionScheme(CachedFeatures cf) {
    this.cachedFeatures = cf;
    this.alph = new Alphabet<>();
    int updateInterval = 32;
    int d = 1 << 20;
    wHatch = new LazyL2UpdateVector(new IntDoubleDenseVector(d), updateInterval);
    wSquash = new LazyL2UpdateVector(new IntDoubleDenseVector(d), updateInterval);
  }

  public void set(Info info) {
    this.info = info;
    this.sentenceLen = info.getSentence().size();
  }

  private int encodeSpan(Span s) {
    return s.end + s.start * sentenceLen;
  }
  private Span decodeSpan(int i) {
    if (i <= 0)
      throw new RuntimeException();
    int e = i % sentenceLen;
    int s = i / sentenceLen;
    return Span.getSpan(s, e);
  }

  @Override
  public LL<TV> consPrefix(TV car, LL<TV> cdr) {
    return new TFKS(car, (TFKS) cdr);
  }

  @Override
  public LL<Node2> consChild(Node2 car, LL<Node2> cdr) {
    if (car.getType() == TFKS.K)
      return new RoleLL(car, cdr, getPrimes);
    return new PrimesLL(car, cdr, getPrimes);
  }

  @Override
  public NodeWithSignature newNode(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children) {
    return new NodeWithSignature(prefix, eggs, pruned, children);
  }

  @Override
  public Iterable<LL<TV>> encode(FNParse y) {
    List<LL<TV>> yy = new ArrayList<>();
    for (FrameInstance fi : y.getFrameInstances()) {
      Frame fr = fi.getFrame();
      int f = fr.getId();
      int t = encodeSpan(fi.getTarget());
      int K = fr.numRoles();
      for (int k = 0; k < K; k++) {
        Span a = fi.getArgument(k);
        if (a != Span.nullSpan) {
          int s = encodeSpan(a);
          yy.add(Node2Tests.lltvSugar(TFKS.S, s, TFKS.K, k+0*K, TFKS.F, f, TFKS.T, t));
        }
        for (Span ca : fi.getContinuationRoleSpans(k)) {
          int s = encodeSpan(ca);
          yy.add(Node2Tests.lltvSugar(TFKS.S, s, TFKS.K, k+1*K, TFKS.F, f, TFKS.T, t));
        }
        for (Span ra : fi.getReferenceRoleSpans(k)) {
          int s = encodeSpan(ra);
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
  public LL<TV> genEggs(LL<TV> prefix) {
    LL<TV> l = null;

    // () -> T
    if (prefix == null) {
      // TODO Sort these by static model score
      for (Span ts : info.getPossibleTargets()) {
        int t = encodeSpan(ts);
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
      t = decodeSpan(p.t);
      for (Frame ff : info.getPossibleFrames(t))
        l = new LL<>(new TV(TFKS.F, ff.getId()), l);
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
      t = decodeSpan(p.t);
      f = fi.getFrame(p.f);
      for (Span ss : info.getPossibleArgs(f, t)) {
        int s = encodeSpan(ss);
        l = new LL<>(new TV(TFKS.S, s), l);
      }
      return l;
    case TFKS.S:
      return null;
    default:
      throw new RuntimeException();
    }
  }

  @Override
  public Info getInfo() {
    return info;
  }

  private List<String> feats(Node2 n) {
    TFKS p = (TFKS) n.prefix;
    List<String> feats = new ArrayList<>();
    TV egg = n.eggs.car();
    feats.add("intercept_" + egg.getType());
    Frame f;
    switch (egg.getType()) {
    case TFKS.K:
      f = info.getFrameIndex().getFrame(p.f);
      feats.add("K * f=" + f.getName());
      String role = f.getRole(p.k);
      feats.add("K * f=" + f.getName() + " * k=" + role);
      break;
    case TFKS.F:
      f = info.getFrameIndex().getFrame(p.f);
      feats.add("F * f=" + f.getName());
      break;
    }
    return feats;
  }

  @Override
  public Adjoints featsHatch(Node2 n) {
    
    
    
    throw new RuntimeException("finish this");
  }

  @Override
  public Adjoints featsSquash(Node2 n) {
    throw new RuntimeException("finish this");
  }

}
