package edu.jhu.hlt.fnparse.rl.full.weights;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;

// TODO Try array and hashmap implementations, compare runtime
public interface StaticFeatureCache {
  public Adjoints scoreT(Span t);
  public Adjoints scoreTF(Span t, Frame f);
  public Adjoints scoreTS(Span t, Span s);
  public Adjoints scoreFK(Frame f, int k, int q);
  public Adjoints scoreFKS(Frame f, int k, int q, Span s);
  public Adjoints scoreTFKS(Span t, Frame f, int k, int q, Span s);
  // etc?
  public List<ProductIndex> featT(Span t);
  public List<ProductIndex> featTF(Span t, Frame f);
  public List<ProductIndex> featTS(Span t, Span s);
  public List<ProductIndex> featFK(Frame f, int k, int q);
  public List<ProductIndex> featFKS(Frame f, int k, int q, Span s);
  public List<ProductIndex> featTFKS(Span t, Frame f, int k, int q, Span s);
}