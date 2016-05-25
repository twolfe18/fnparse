package edu.jhu.hlt.uberts.factor;

import java.util.List;
import java.util.function.Function;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;

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
  public static boolean COARSE_DEBUG = true;
  public static boolean DEBUG = false;

  /**
   * @param u receives the new {@link GlobalFactor}
   * @param rel is the relation which has mutually exclusive facts,
   *        e.g. predicate2(t,f)
   * @param mutexArg is the index of the argument describing the scope of mutual
   *        exclusion, e.g. 0 to represent t in predicate2(t,f)
   */
  public static void add(Uberts u, Relation rel, int mutexArg) {
    assert mutexArg >= 0 && mutexArg < rel.getNumArgs();
    TKey[] lhs = new TKey[] {
        new TKey(State.HEAD_ARG_POS, rel),
    };
    GlobalFactor gf = new AtMost1.RelNode1(rel, gtt -> {
      HypEdge pred2Fact = gtt.getBoundEdge(0);
      HypNode t = pred2Fact.getTail(mutexArg);
      return t;
    });
    u.addGlobalFactor(lhs, gf);
  }

  // Hard constraint
  public static class RelNode1 implements GlobalFactor {
    // Prune all edges that match this:
    private Relation relationMatch;
    // And are adjacent to this node:
    private Function<GraphTraversalTrace, HypNode> getBoundNode;

    public RelNode1(Relation relation, Function<GraphTraversalTrace, HypNode> getBoundNode) {
      this.relationMatch = relation;
      this.getBoundNode = getBoundNode;
    }

    public void rescore(Agenda a, GraphTraversalTrace match) {
      HypNode observedValue = getBoundNode.apply(match);
      if (DEBUG)
        Log.info("removing all edges adjacent to " + observedValue + " matching " + relationMatch + " from agenda");
      int c = 0, r = 0;
      for (HypEdge e : a.adjacent(observedValue)) {
        c++;
        if (e.getRelation() == relationMatch) {
          if (DEBUG)
            Log.info("actually removing: " + e);
          r++;
          a.remove(e);
        }
      }
      if (DEBUG || COARSE_DEBUG) {
        Log.info("removed " + r + " of " + c + " " + relationMatch.getName()
            + " edges adjacent to " + observedValue);
      }
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
      if (DEBUG)
        Log.info("INTERESTING");
      HypNode bound1 = getBoundNode1.apply(match);
      HypNode bound2 = getBoundNode2.apply(match);
      List<HypEdge> intersect = a.adjacent(bound1, bound2);
      int c = 0, r = 0;
      for (HypEdge e : intersect) {
        c++;
        if (e.getRelation() == relationMatch) {
          r++;
          if (DEBUG)
            Log.info("actually removing: " + e);
          a.remove(e);
        }
      }
      if (DEBUG || COARSE_DEBUG) {
        Log.info("removed " + r + " of " + c + " " + relationMatch.getName()
          + " edges adjacent to " + bound1 + " and " + bound2);
      }
    }
  }

}
