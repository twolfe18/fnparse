package edu.jhu.hlt.fnparse.rl;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;

public class StateSequence {

  private StateSequence prev, next;
  private State cur;
  private int movesApplied = -1;

  // Contains an Action which is
  // either (prev -> action -> cur)
  // or     (cur -> action -> next)   <= currently deprecated
  private Adjoints action;

  // Indexes all the COMMIT Actions in this sequence for the use by features.
  // TODO Have another index for PRUNE Actions -- can't do that with SpanIndex
  //private SpanIndex<Action> actionIndex;
  private CommitIndex actionIndex;

  public StateSequence(StateSequence prev, StateSequence next, State cur, Adjoints action) {
    assert !(prev != null && next != null);
    this.prev = prev;
    this.next = next;
    this.cur = cur;
    this.action = action;
    this.actionIndex = null;    // call initActionIndex*
  }

  public void initActionIndexFromPrev() {
    assert next == null : "did you decide to do bi-directional search again?";
    Action a = action.getAction();
    if (a.mode == ActionType.COMMIT.getIndex()) {
      actionIndex = prev.actionIndex.persistentUpdate(action.getAction());
    } else {
      assert a.mode == ActionType.PRUNE.getIndex();
      actionIndex = prev.actionIndex;
    }
  }

  public void initActionIndexFromScratch() {
    //actionIndex = new SpanIndex<>(cur.getSentence().size());
    actionIndex = new CommitIndex(cur.getSentence().size());
  }

  //public SpanIndex<Action> getActionIndex() {
  public CommitIndex getActionIndex() {
    return actionIndex;
  }

  // This is now in Reranker.Update's constructor
//  public double violation(FNParse y, double modelScoreOfY) {
//  /*
//   * Sum of the local hinge losses for every action.
//   *
//   * I shouldn't have to have a local margin! The whole point of putting constraints
//   * on every z (including non-terminal z) is that these are smaller constraints
//   * that do not allow the large-structure-margin problem [1] to happen.
//   *
//   * [1] In a large structure, you can easily satisfy a margin on the total loss
//   * function by putting a very high deltaScore on easy indices in the label and
//   * meet no local margin at all for difficult indices in the label.
//   */
//
//    // Global margin (must consider all z -- including non-terminals -- if you
//    // want this to work).
//    return Math.max(0, (getScore() + getHamming(y)) - modelScoreOfY) * getLoss(y);
//
//    // Local margin (useful if you only consider terminal z)
////    if (violation < 0) {
////      State prev = neighbor().getCur();
////      Action a = getAction();
////      double deltaLoss  = a.getActionType().deltaLoss(prev, a, y);
////      double signedMargin = deltaLoss == 0
////          ? (-action.forwards() + 1)
////          : (+action.forwards() - 1);
////      double cost = Math.max(1, deltaLoss);   // slope of hinge loss
////      violation = Math.max(0, signedMargin) * cost
////          + neighbor().violation(y);
////    }
////    return violation;
//  }
////  private double violation = -1;

  // I don't think I need this anywhere
//  /**
//   * Returns the number of z, or parser actions, that lead to non-zero loss.
//   */
//  public int getHamming(FNParse y) {
//    if (hamming < 0) {
//      if (neighbor() == null) {
//        hamming = 0;
//      } else {
//        State prev = neighbor().getCur();
//        Action a = getAction();
//        double dl = a.getActionType().deltaLoss(prev, a, y);
//        int h = (dl > 0) ? 1 : 0;
//        hamming = h + neighbor().getHamming(y);
//      }
//    }
//    return hamming;
//  }
//  private int hamming = -1;

  /**
   * Returns the accumulated loss of this trajectory so far. If this is a final
   * state (i.e. yHat = this.getCur().decode() is non-null), then this should be
   * equal to se.numFP + costFN + se.numFN, where se = SentenceEval(y, yHat).
   * If this is an oracle trajectory, this should return 0.
   */
  public double getLoss(FNParse y) {
    if (loss < 0) {
      if (neighbor() == null) {
        loss = 0;
      } else {
        State prev = neighbor().getCur();
        Action a = getAction();
        double dl = a.getActionType().deltaLoss(prev, a, y);
        loss = dl + neighbor().getLoss(y);
      }
    }
    return loss;
  }
  private double loss = -1;

  /** You can only call this from a node which has a pointer out of it */
  public String showActions() {
    FNTagging frames = getCur().getFrames();
    StringBuilder sb = null;
    for (StateSequence cur = this; cur != null; cur = cur.neighbor()) {
      String a = (cur.action == null) ? "???" : cur.getAction().show(frames);
      if (sb == null) {
        sb = new StringBuilder(a);
      } else {
        sb.append(", ");
        sb.append(a);
      }
    }
    return sb.toString();
  }

  /** You can only call this from a node which has a pointer out of it */
  public int length() {
    StateSequence l = this;
    int len = 0;
    while (l != null) {
      len++;
      l = l.neighbor();
    }
    len--;
    return len;
  }

  public State getCur() {
    if (cur == null) {
      // Lazily compute States
      StateSequence n = neighbor();
      boolean forwards = (n == prev);
      cur = n.getCur().apply(action.getAction(), forwards);
    }
    return cur;
  }

  public StateSequence getPrev() {
    return prev;
  }

  public StateSequence getNext() {
    return next;
  }

  public void setPrev(StateSequence prev) {
    assert this.prev == null && this.next == null;
    this.prev = prev;
  }

  public void setNext(StateSequence next) {
    assert this.prev == null && this.next == null;
    this.next = next;
  }

  public int getMovesApplied() {
    if (movesApplied < 0)
      movesApplied = 1 + neighbor().getMovesApplied();
    return movesApplied;
  }

  public StateSequence neighbor() {
    if (next == null && prev == null)
      return null;
    else if (next == null && prev != null)
      return prev;
    else if (next != null && prev == null)
      return next;
    else
      throw new RuntimeException("ambiguous");
  }

  private double score = Double.NaN;  // memo
  /**
   * @return the sum of the Actions' scores in this sequence.
   */
  public double getScore() {
    if (Double.isNaN(score)) {
      if (action == null)
        return 0d;
      StateSequence n = neighbor();
      score = action.forwards() +  (n == null ? 0d : n.getScore());
      assert Double.isFinite(score) && !Double.isNaN(score);
    }
    return score;
  }

  public Adjoints getAdjoints() {
    return action;
  }

  public Action getAction() {
    return action.getAction();
  }

  public Action getActionSafe() {
    if (action == null)
      return null;
    return action.getAction();
  }

  public StateSequence getLast() {
    StateSequence cur = this;
    while (cur.next != null)
      cur = cur.next;
    return cur;
  }

  public StateSequence getFirst() {
    StateSequence cur = this;
    while (cur.prev != null)
      cur = cur.prev;
    return cur;
  }

  /** Like getFirst but sets next pointers on the way back */
  public StateSequence rewind() {
    StateSequence next = null;
    StateSequence cur = this;
    while (cur != null) {
      cur.next = next;
      next = cur;
      cur = cur.prev;
    }
    return next;
  }
}