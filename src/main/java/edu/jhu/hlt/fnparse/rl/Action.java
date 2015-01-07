package edu.jhu.hlt.fnparse.rl;

import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * represents a constraint on the set of arguments a particular
 * (frame,target,role) can take on.
 */
public class Action {

  public int t;     // (frame,target), i.e. frameInstance index
  public int k;     // role
  public int mode;  // see ActionType.getIndex and ActionType.ACTION_TYPES
  public int start;
  public int end;

  public Action(int t, int k, int mode, Span s) {
    this.t = t;
    this.k = k;
    this.mode = mode;
    this.start = s.start;
    this.end = s.end;
    assert start >= 0;
    assert start < end || s == Span.nullSpan;
  }

  public boolean hasSpan() {
    return !Span.nullSpan.equals(start, end);
  }

  public Span getSpanSafe() {
    if (Span.nullSpan.equals(start, end))
      return Span.nullSpan;
    return getSpan();
  }

  public Span getSpan() {
    assert hasSpan();
    return Span.getSpan(start, end);
  }

  public int width() {
    return end - start;
  }

  public boolean matches(Span s) {
    return s.start == start && s.end == end;
  }

  public ActionType getActionType() {
    return ActionType.ACTION_TYPES[mode];
  }

  public String toString() {
    String m = ActionType.ACTION_TYPES[mode].getName();
    return String.format("[Action(%s) t=%d k=%d %d-%d]", m, t, k, start, end);
  }
}