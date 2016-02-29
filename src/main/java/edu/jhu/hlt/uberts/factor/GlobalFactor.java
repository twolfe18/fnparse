package edu.jhu.hlt.uberts.factor;

import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;

public interface GlobalFactor {
  /** Do whatever you want to the edges in the agenda */
  public void rescore(Agenda a, GraphTraversalTrace match);
}