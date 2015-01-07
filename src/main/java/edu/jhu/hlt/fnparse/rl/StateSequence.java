package edu.jhu.hlt.fnparse.rl;

import edu.jhu.hlt.fnparse.rl.params.Adjoints;

public class StateSequence {

  private StateSequence prev, next;
  private State cur;
  private int movesApplied = -1;

  // The action + features + computation
  // either (cur -> action -> next) if next != null
  // or     (prev -> action -> cur) if prev != null
  // TODO this definition is not compatible with neighbor(), choose one version
  private Adjoints action;

  public StateSequence(StateSequence prev, StateSequence next, State cur, Adjoints action) {
    assert !(prev != null && next != null);
    this.prev = prev;
    this.next = next;
    this.cur = cur;
    this.action = action;
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
   * Returns the sum of the Actions' scores in this sequence.
   * @return
   */
  public double getScore() {
    if (Double.isNaN(score)) {
      StateSequence n = neighbor();
      if (action == null) {
        return 0d;
      }
      score = action.getScore() +  (n == null ? 0d : n.getScore());
    }
    return score;
  }

  public Adjoints getAdjoints() {
    return action;
  }

  public Action getAction() {
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
}