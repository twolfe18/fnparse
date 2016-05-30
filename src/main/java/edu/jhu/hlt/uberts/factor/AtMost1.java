package edu.jhu.hlt.uberts.factor;

import java.util.List;
import java.util.function.Function;

import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
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
  public static int DEBUG = 1;

  /**
   * @param u receives the new {@link GlobalFactor}
   * @param rel is the relation which has mutually exclusive facts,
   *        e.g. predicate2(t,f)
   * @param mutexArg is the index of the argument describing the scope of mutual
   *        exclusion, e.g. 0 to represent t in predicate2(t,f)
   */
  public static void add(Uberts u, Relation rel, int mutexArg, boolean hard) {
    if (DEBUG > 0) {
      Log.info("AtMost1 " + rel.getName() + " per "
          + rel.getTypeForArg(mutexArg).getName() + " hard=" + hard);
    }
    assert mutexArg >= 0 && mutexArg < rel.getNumArgs();
    TKey[] lhs = new TKey[] {
        new TKey(State.HEAD_ARG_POS, rel),
    };
    GlobalFactor gf = new AtMost1.RelNode1(rel, hard, gtt -> {
      HypEdge pred2Fact = gtt.getBoundEdge(0);
      HypNode t = pred2Fact.getTail(mutexArg);
      return t;
    }, rel.getTypeForArg(mutexArg).getName());
    u.addGlobalFactor(lhs, gf);
  }

  public static class RelNode1 implements GlobalFactor {
    // Prune all edges that match this:
    private Relation relationMatch;
    // And are adjacent to this node:
    private Function<GraphTraversalTrace, HypNode> getBoundNode;
    private String boundNodeName;

    private int nRescore = 0, nEdgeRescore = 0;

    private AveragedPerceptronWeights constraintCost;
    private String constraintCostName;
    String getConstraintCostName() {
      if (constraintCostName == null)
        constraintCostName = getName();
      return constraintCostName;
    }

    @Override
    public String getStats() {
      String s = "nRescore=" + nRescore + " nEdgeRescore=" + nEdgeRescore;
      nRescore = 0;
      nEdgeRescore = 0;
      return s;
    }

    @Override
    public String toString() {
      return getName();
    }

    @Override
    public String getName() {
      return "AtMost1(" + relationMatch.getName() + "," + boundNodeName + ")";
    }

    /**
     * @param relation
     * @param hard true if you want the HypEdge simply removed from the agenda,
     * otherwise a weight is learned.
     * @param getBoundNode should be a function which finds the HypNode over which
     * mutual exclusion is enforced. Typically this is an argument of relation.
     */
    public RelNode1(Relation relation, boolean hard, Function<GraphTraversalTrace, HypNode> getBoundNode, String nameOfBoundNode) {
      this.relationMatch = relation;
      this.getBoundNode = getBoundNode;
      this.boundNodeName = nameOfBoundNode;
      if (!hard) {
        int dimension = 1;
        int numIntercept = 0;
        constraintCost = new AveragedPerceptronWeights(dimension, numIntercept);
      }
    }

    public void rescore(Agenda a, GraphTraversalTrace match) {
      HypNode observedValue = getBoundNode.apply(match);
      if (DEBUG > 1)
        Log.info("removing all edges adjacent to " + observedValue + " matching " + relationMatch + " from agenda");
      nRescore++;
      int c = 0, r = 0;
      for (HypEdge e : a.adjacent(observedValue)) {
        c++;
        if (e.getRelation() == relationMatch) {
          nEdgeRescore++;
          r++;
          if (constraintCost != null) {
            // Soft
            if (DEBUG > 2)
              Log.info("rescoring: " + e);
            Adjoints score = a.getScore(e);
            a.remove(e);
            if (!(score instanceof GlobalFactorAdjoints))
              score = new GlobalFactorAdjoints(score);
            GlobalFactorAdjoints gs = (GlobalFactorAdjoints) score;
            String n = getConstraintCostName();
            int[] features = new int[] {0};
            boolean reindex = false;
            gs.addToGlobalScore(n, constraintCost.score(features, reindex));
            a.add(e, gs);
          } else {
            // Hard
            if (DEBUG > 2)
              Log.info("removing: " + e);
            a.remove(e);
          }
        }
      }
      if (DEBUG > 1) {
        if (constraintCost == null) {
          Log.info("removed " + r + " of " + c + " " + relationMatch.getName()
          + " edges adjacent to " + observedValue);
        } else {
          Log.info("added " + constraintCost.getWeight(0) + " to the score of "
              + c + " " + relationMatch + " edges adjacent to " + observedValue);
        }
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
    private String boundNode1Name;
    private String boundNode2Name;
    private int nRescore = 0, nEdgeRescore = 0;

    @Override
    public String getStats() {
      String s = "nRescore=" + nRescore + " nEdgeRescore=" + nEdgeRescore;
      nRescore = 0;
      nEdgeRescore = 0;
      return s;
    }

    @Override
    public String getName() {
      return "AtMost1(" + relationMatch.getName() + "," + boundNode1Name + "," + boundNode2Name + ")";
    }

    @Override
    public String toString() {
      return getName();
    }

    public RelNode2(Relation relationMatch,
        Function<GraphTraversalTrace, HypNode> getBoundNode1,
        Function<GraphTraversalTrace, HypNode> getBoundNode2,
        String boundNode1Name,
        String boundNode2Name) {
      this.relationMatch = relationMatch;
      this.getBoundNode1 = getBoundNode1;
      this.getBoundNode2 = getBoundNode2;
      this.boundNode1Name = boundNode1Name;
      this.boundNode2Name = boundNode2Name;
    }
    @Override
    public void rescore(Agenda a, GraphTraversalTrace match) {
      nRescore++;
      HypNode bound1 = getBoundNode1.apply(match);
      HypNode bound2 = getBoundNode2.apply(match);
      List<HypEdge> intersect = a.adjacent(bound1, bound2);
      int c = 0, r = 0;
      for (HypEdge e : intersect) {
        c++;
        if (e.getRelation() == relationMatch) {
          r++;
          if (DEBUG > 2)
            Log.info("actually removing: " + e);
          nEdgeRescore++;
          a.remove(e);
        }
      }
      if (DEBUG > 1) {
        Log.info("removed " + r + " of " + c + " " + relationMatch.getName()
          + " edges adjacent to " + bound1 + " and " + bound2);
      }
    }
  }

}
