package edu.jhu.hlt.uberts.factor;

import java.util.List;
import java.util.function.Function;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.stanford.nlp.ling.tokensregex.SequenceMatchAction.BoundAction;

/**
 * Running examples:
 * pos(i,X) ^ diff(X,Y) => !pos(i,Y)
 * ner(i,j,T) ^ diff(T,S) => !pos(i,j,S)
 *
 * I think I have the right implementation for the pos example:
 *   match on pos(i,T) and remove every 1) pos edge that 2) is a neighbor of i and 3) doesn't match T
 *
 * For ner, there are a few things I could want:
 * 1) every span has <=1 tag
 * 2) spans don't overlap
 * Lets just target 1 for now.
 * If I match on tail=[ner:relation], that is strong enough:
 * from the presence of a relation, i can easily pull out (i,j),
 * and then I can go look for ner edges in inersect(adjacent(tokenIndex i),adjacent(tokenIndex j))
 */
public class AtMost1 {

  // Hard constraint
  public static class RelNode1 implements GlobalFactor {
    // Prune all edges that match this:
    private Relation relationMatch;
    // And are adjacent to this node:
    private Function<GraphTraversalTrace, HypNode> getBoundNode;

    public RelNode1(Relation twoArgRelation, Function<GraphTraversalTrace, HypNode> getBoundNode) {
      if (twoArgRelation.getNumArgs() != 2)
        throw new IllegalArgumentException();
      this.relationMatch = twoArgRelation;
      this.getBoundNode = getBoundNode;
    }

    public void rescore(Agenda a, GraphTraversalTrace match) {
      HypNode observedValue = getBoundNode.apply(match);
      Log.info("removing all edges adjacent to " + observedValue + " matching " + relationMatch + " from agenda");
      int c = 0, r = 0;
      for (HypEdge e : a.adjacent(observedValue)) {
        c++;
        if (e.getRelation() == relationMatch) {
          Log.info("actually removing: " + e);
          r++;
          a.remove(e);
        }
      }
      Log.info("removed " + r + " of " + c + " edges adjacent to " + observedValue);
    }
  }

  // Hard constraint
  public static class RelNode2 implements GlobalFactor {
    // Prune all edges that match this:
    private Relation relationMatch;
    // And are adjacent to these nodes:
    private Function<GraphTraversalTrace, HypNode> getBoundNode1;
    private Function<GraphTraversalTrace, HypNode> getBoundNode2;
    public RelNode2(Relation relationMatch,
        Function<GraphTraversalTrace, HypNode> getBoundNode1,
        Function<GraphTraversalTrace, HypNode> getBoundNode2) {
      this.relationMatch = relationMatch;
      this.getBoundNode1 = getBoundNode1;
      this.getBoundNode2 = getBoundNode2;
    }
    @Override
    public void rescore(Agenda a, GraphTraversalTrace match) {
      Log.info("INTERESTING");
      HypNode bound1 = getBoundNode1.apply(match);
      HypNode bound2 = getBoundNode2.apply(match);
      List<HypEdge> intersect = a.adjacent(bound1, bound2);
      int c = 0, r = 0;
      for (HypEdge e : intersect) {
        c++;
        if (e.getRelation() == relationMatch) {
          r++;
          Log.info("actually removing: " + e);
          a.remove(e);
        }
      }
      Log.info("removed " + r + " of " + c + " edges adjacent to " + bound1 + " and " + bound2);
    }
  }

}
