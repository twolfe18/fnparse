package edu.jhu.hlt.uberts.srl;

import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.Relation.EqualityArray;
import edu.jhu.hlt.uberts.features.OldFeaturesWrapper;

/**
 * This is confusing: implementation is tied to an older grammar where srl3
 * represented all info (hence fields for t,f,s,k). No we use srl3(t,f,k), but
 * that also might change.
 *
 * @deprecated
 *
 * @author travis
 */
public class Srl3EdgeWrapper {

  public final HypEdge e;
  public final Span t, s;
  public final String f;
  public final String k;

  public Srl3EdgeWrapper(HypEdge e) {
    if (!e.getRelation().getName().equals("srl3"))
      throw new IllegalArgumentException();
    assert e.getNumTails() == 3;  // (s2, e2, k) or (t,f,k)
    this.e = e;
    Object arg0 = e.getTail(0).getValue();
    Object arg1 = e.getTail(1).getValue();
    Object arg2 = e.getTail(2).getValue();
    if (arg0 instanceof String && arg1 instanceof String && arg2 instanceof String) {
      // (t,f,k)
      s = null;
      t = Span.inverseShortString((String) arg0);
      f = (String) arg1;
      k = (String) arg2;
    } else {
      EqualityArray s2 = (EqualityArray) e.getTail(0).getValue();
      EqualityArray e2 = (EqualityArray) e.getTail(1).getValue();
      EqualityArray s1 = (EqualityArray) s2.get(0);
      EqualityArray e1 = (EqualityArray) s2.get(1);
      f = (String) e2.get(1);
      k = (String) e.getTail(2).getValue();
      t = OldFeaturesWrapper.extractSpan(e1, 0, 1);
      s = OldFeaturesWrapper.extractSpan(s1, 0, 1);
    }
  }

}
