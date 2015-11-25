package edu.jhu.hlt.fnparse.rl.full.weights;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.full.State;
import edu.jhu.hlt.fnparse.rl.full.State.FeatType;
import edu.jhu.hlt.tutils.scoring.Adjoints;

public abstract class AbstractStaticFeatureCache
    extends WeightsMatrix<FeatType>
    implements StaticFeatureCache {

  @Override public int numRows() { return FeatType.values().length; }
  @Override public int row(FeatType t) { return t.ordinal(); }

  public Adjoints scoreT(Span t) {
    return getScore(FeatType.T, featT(t));
  }
  public Adjoints scoreTF(Span t, Frame f) {
    return getScore(FeatType.TF, featTF(t, f));
  }
  public Adjoints scoreTS(Span t, Span s) {
    return getScore(FeatType.TS, featTS(t, s));
  }
  public Adjoints scoreFK(Frame f, int k, int q) {
    return getScore(FeatType.FK, featFK(f, k, q));
  }
  public Adjoints scoreFKS(Frame f, int k, int q, Span s) {
    return getScore(FeatType.FKS, featFKS(f, k, q, s));
  }
  public Adjoints scoreTFKS(Span t, Frame f, int k, int q, Span s) {
    return getScore(FeatType.TFKS, featTFKS(t, f, k, q, s));
  }
}