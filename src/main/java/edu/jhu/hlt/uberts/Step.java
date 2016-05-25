package edu.jhu.hlt.uberts;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.tuple.Pair;

/**
 * Represents an action taken at a state.
 *
 * TODO: May want to take pred:boolean as an argument if I decide not to use
 * 0 as the threshold for all relations.
 *
 * @author travis
 */
public class Step {
  public final HypEdge edge;
  public final Adjoints score;
  public final boolean pred;    // true means Commit, false means Prune
  public final Boolean gold;    // can be null if you don't know

  public Step(Pair<HypEdge, Adjoints> es, Boolean gold, boolean pred) {
    this(es.get1(), es.get2(), gold, pred);
  }

  public Step(HypEdge e, Adjoints score, Boolean gold, boolean pred) {
    this.edge = e;
    this.score = score;
    this.gold = gold;
    this.pred = pred;
  }

  @Override
  public String toString() {
    return "(Step " + edge + " score=" + score + " gold=" + gold + ")";
  }
}