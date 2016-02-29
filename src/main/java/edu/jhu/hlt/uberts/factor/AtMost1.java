package edu.jhu.hlt.uberts.factor;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;

/** Only works for 2 arg relations now */
public class AtMost1 implements GlobalFactor {

  // Right now this is pos(i,T) => !pos(i,S) s.t. S \not\eq T
  private Relation rel2;
  //    private NodeType freeVar;   // range over which AtMost1 applies
  private NodeType boundVar;  // should be able to find this value in matches

  public AtMost1(Relation twoArgRelation, NodeType range) {
    if (twoArgRelation.getNumArgs() != 2)
      throw new IllegalArgumentException();
    this.rel2 = twoArgRelation;
    this.boundVar = range;
  }

  public void rescore(Agenda a, GraphTraversalTrace match) {
    HypNode observedValue = match.getValueFor(boundVar);
    Log.info("removing all edges adjacent to " + observedValue + " matching " + rel2 + " from agenda");
    int c = 0;
    for (HypEdge e : a.adjacent(observedValue)) {
//      Log.info("checking " + e);
      c++;
      if (e.getRelation() == rel2) {
        Log.info("actually removing: " + e);
        a.remove(e);
      }
    }
    Log.info("there were " + c + " adjacent edges");
  }
}