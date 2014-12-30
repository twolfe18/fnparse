package edu.jhu.hlt.fnparse.rl;

import edu.jhu.hlt.fnparse.datatypes.FNParse;

public class StateSequence {

  private StateSequence prev, next;
  private State cur;
  private int movesApplied;

  // The action + features + computation
  // either (cur -> action -> next) if next != null
  // or     (prev -> action -> cur) if prev != null
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
      cur = n.getCur().resultingFrom(action.getAction());
    }
    return cur;
  }

  private StateSequence neighbor() {
    if (next == null && prev == null)
      return null;
    else if (next == null && prev != null)
      return prev;
    else if (next != null && prev == null)
      return next;
    else
      throw new RuntimeException("ambiguous");
  }

  // TODO memoize this
  public double getScore() {
    StateSequence n = neighbor();
    if (action == null) {
      return 0d;
    }
    return action.getScore() +  (n == null ? 0d : n.getScore());
  }

  public FNParse decode() {
    /*
  List<FrameInstance> fis = new ArrayList<>();
  for (int t = 0; t < frames.numFrameInstances(); t++) {
    FrameInstance fi = frames.getFrameInstance(t);
    Frame f = fi.getFrame();
    fis.add(FrameInstance.newFrameInstance(f, fi.getTarget(), committed[t], getSentence()));
  }
  return new FNParse(getSentence(), fis);
     */
    // TODO this will be implemented as 
    throw new RuntimeException("implement me");
  }
}