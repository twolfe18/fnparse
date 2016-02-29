package edu.jhu.hlt.uberts.factor;

import java.util.function.Function;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;

/** Only works for 2 arg relations now */
public class AtMost1 implements GlobalFactor {

  // Right now this is pos(i,T) => !pos(i,S) s.t. S \not\eq T
  private Relation rel2;

//  private NodeType boundVar;  // should be able to find this value in matches

  // No longer can get HypNode from NodeType in GraphTraversalTrace, require
  // the user to provide this (it is natural to define this function when you
  // define the graph fragment).
  private Function<GraphTraversalTrace, HypNode> getBoundNode;

//  public AtMost1(Relation twoArgRelation, NodeType range) {
  public AtMost1(Relation twoArgRelation, Function<GraphTraversalTrace, HypNode> getBoundNode) {
    if (twoArgRelation.getNumArgs() != 2)
      throw new IllegalArgumentException();
    this.rel2 = twoArgRelation;
//    this.boundVar = range;
    this.getBoundNode = getBoundNode;
  }

  public void rescore(Agenda a, GraphTraversalTrace match) {
//    HypNode observedValue = match.getValueFor(boundVar);
    HypNode observedValue = getBoundNode.apply(match);
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