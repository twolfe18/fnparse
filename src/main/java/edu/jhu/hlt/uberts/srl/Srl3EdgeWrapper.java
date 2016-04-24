package edu.jhu.hlt.uberts.srl;

import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.Relation.EqualityArray;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;

public class Srl3EdgeWrapper {

  public final HypEdge e;
  public final Span t, s;
  public final String f;
  public final String k;

  public Srl3EdgeWrapper(HypEdge e) {
    if (!e.getRelation().getName().equals("srl3"))
      throw new IllegalArgumentException();
    assert e.getNumTails() == 3;  // (s2, e2, k)
    this.e = e;
    EqualityArray s2 = (EqualityArray) e.getTail(0).getValue();
    EqualityArray e2 = (EqualityArray) e.getTail(1).getValue();
    EqualityArray s1 = (EqualityArray) s2.get(0);
    EqualityArray e1 = (EqualityArray) s2.get(1);
    f = (String) e2.get(1);
    k = (String) e.getTail(2).getValue();
    t = FeatureExtractionFactor.extractSpan(e1, 0, 1);
    s = FeatureExtractionFactor.extractSpan(s1, 0, 1);
  }

}
