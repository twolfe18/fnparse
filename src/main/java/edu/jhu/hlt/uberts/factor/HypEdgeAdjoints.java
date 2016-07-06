package edu.jhu.hlt.uberts.factor;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.Uberts;

public class HypEdgeAdjoints implements Adjoints {

  private Adjoints wrapped;
  private HashableHypEdge edge;

  // When backwards is called, update 
  private Uberts phoneHomeWith;

  public HypEdgeAdjoints(HashableHypEdge e, Adjoints s) {
    this.wrapped = s;
    this.edge = e;
  }

  public HypEdgeAdjoints(HashableHypEdge e, Adjoints s, Uberts u) {
    this.wrapped = s;
    this.edge = e;
    this.phoneHomeWith = u;
  }

  public HashableHypEdge getEdge() {
    return edge;
  }

  @Override
  public double forwards() {
    return wrapped.forwards();
  }

  @Override
  public void backwards(double dErr_dForwards) {
    if (phoneHomeWith != null) {
      double d = phoneHomeWith.dbgUpdate.getOrDefault(edge, 0d);
      phoneHomeWith.dbgUpdate.put(edge, d + dErr_dForwards);
    }
    wrapped.backwards(dErr_dForwards);
  }

  @Override
  public String toString() {
    return "(HEAdj " + edge.getEdge() + " " + wrapped + ")";
  }
}
