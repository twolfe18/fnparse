package edu.jhu.hlt.fnparse.rl;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.HasSpan;
import edu.jhu.hlt.tutils.Span;

/**
 * represents a constraint on the set of arguments a particular
 * (frame,target,role) can take on.
 */
public class Action implements HasSpan {

  public int t;     // (frame,target), i.e. frameInstance index
  public int k;     // role
  public int mode;  // see ActionType.getIndex and ActionType.ACTION_TYPES
  public int start;
  public int end;

  private int hashcode;

  public Action(int t, int k, int mode, int start, int end) {
    this.t = t;
    this.k = k;
    this.mode = mode;
    this.start = start;
    this.end = end;

    // Must be last
    this.hashcode = hc1();
    //this.hashcode = hc2();
  }

  public Action(int t, int k, int mode, Span s) {
    this(t, k, mode, s.start, s.end);
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

  @Override
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

  @Override
  public int hashCode() {
    return hashcode;
  }

  /** WAYY better than hc2 */
  public int hc1() {
    return start
      ^ ((end - start) << 8)
      ^ (mode << 14)
      ^ (k << 18)
      ^ (t << 24);
  }
  /** @deprecated this performs terribly */
  public int hc2() {
    // 17 103 211 331 449 587 709 853 991 1117 1270
    return start + (end - start) * 17 + mode * 331 + k * 700 + t * 1279;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof Action) {
      Action a = (Action) other;
      return t == a.t && k == a.k && mode == a.mode && start == a.start && end == a.end;
    }
    return false;
  }

  public String toString() {
    String m = ActionType.ACTION_TYPES[mode].getName();
    return String.format("[Action(%s) t=%d k=%d %d-%d]", m, t, k, start, end);
  }

  public String show(FNTagging frames) {
    FrameInstance fi = frames.getFrameInstance(t);
    Frame f = fi.getFrame();
    String arg = "nullSpan";
    if (hasSpan())
      arg = Describe.span(getSpan(), frames.getSentence());
    return String.format("%s(%s.%s, %s)",
        getActionType().getName(), f.getName(), f.getRole(k), arg);
  }
}