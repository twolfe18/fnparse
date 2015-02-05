package edu.jhu.hlt.fnparse.rl;

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

  // Indexes all the Actions in this sequence for the use by features.
  private ActionIndex actionIndex;

  public StateSequence(StateSequence prev, StateSequence next, State cur, Adjoints action, boolean useActionIndex) {
    assert !(prev != null && next != null);
    this.prev = prev;
    this.next = next;
    this.cur = cur;
    this.action = action;
    if (useActionIndex) {
      assert next == null : "did you decide to do bi-directional search again?";
      if (prev == null)
        actionIndex = new ActionIndex(cur.getSentence().size());
      else
        actionIndex = prev.actionIndex.updateIndex(action.getAction());
    }
  }

  public ActionIndex getActionIndex() {
    return actionIndex;
  }

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