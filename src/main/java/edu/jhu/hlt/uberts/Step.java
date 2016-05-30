package edu.jhu.hlt.uberts;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda.AgendaItem;
import edu.jhu.prim.tuple.Pair;

/**
 * Represents either a Commit(f) or Prune(f) action taken at a state, where f
 * is the HypEdge field. It is a Commit(f) if score > 0, and a Prune(f) otherwise.
 * You can only back-prop an error to score if this is Commit(f) and !gold (FP)
 * or if it is Prune(f) and gold (FN). In the first case you are lowering the
 * score of Commit(f) and in the latter you are raising the score of Commit(f).
 * The score of Prune(f) is fixed at 0. So this really represents two actions
 * and one score.
 *
 * @author travis
 */
public class Step {
  public final HypEdge edge;
  public final Adjoints score;
  public final boolean pred;    // true means Commit, false means Prune
  public final Boolean gold;    // can be null if you don't know
  public final double priority;

  public Step(AgendaItem ai, Boolean gold, boolean pred) {
    this(ai.edge, ai.score, gold, pred, ai.priority);
  }

  public Step(Pair<HypEdge, Adjoints> es, Boolean gold, boolean pred, double priority) {
    this(es.get1(), es.get2(), gold, pred, priority);
  }

  public Step(HypEdge e, Adjoints score, Boolean gold, boolean pred, double priority) {
    this.edge = e;
    this.score = score;
    this.gold = gold;
    this.pred = pred;
    this.priority = priority;
  }

  public boolean isCommit() {
    return score.forwards() > 0;
  }
  public boolean isPrune() {
    return !isCommit();
  }
  public boolean isFP() {
    return !gold && isCommit();
  }
  public boolean isFN() {
    return gold && isPrune();
  }
  public boolean hasLoss() {
    return isFP() || isFN();
  }

  @Override
  public String toString() {
    return "(Step " + edge + " score=" + score + " gold=" + gold + ")";
  }
}