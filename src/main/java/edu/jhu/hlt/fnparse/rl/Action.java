package edu.jhu.hlt.fnparse.rl;

import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * represents a constraint on the set of arguments a particular
 * (frame,target,role) can take on.
 */
public class Action {

  // NOTE: Keep these as ints, as I may want to pack them into start

  public static final int COMMIT = 0;   // assign a span or nullSpan

  // Prune any spans that partially overlap with the span being added.
  // Not compatible with nullSpan.
  public static final int COMMIT_AND_PRUNE_OVERLAPPING = 1;

  public static final int PRUNE_ENTIRE_SENTENCE = -1;
  public static final int PRUNE_LEFT_OF_TARGET = -2;
  public static final int PRUNE_RIGHT_OF_TARGET = -3;
  public static final int PRUNE_SPANS_WIDER_THAN = -8;
  public static final int PRUNE_SPANS_STARTING_WITH_POS = -9;
  public static final int PRUNE_SPANS_ENDING_WITH_POS = -10;


  public final int t;     // (frame,target), i.e. frameInstance index
  public final int k;     // role
  public final int mode;  // see above, most common is COMMIT
  public final int start;
  public final int end;

  public Action(int t, int k, Span s) {
    this.t = t;
    this.k = k;
    this.mode = COMMIT;
    this.start = s.start;
    this.end = s.end;
    assert start >= 0;
    assert start < end || s == Span.nullSpan;
  }

  public boolean hasSpan() {
    if (mode == COMMIT) {
      return start < end; // else its nullSpan
    }
    if (mode == COMMIT_AND_PRUNE_OVERLAPPING) {
      assert start < end;
      return true;
    }
    return false;
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

  public String toString() {
    String m = mode == COMMIT ? "COMMIT" : "???";
    return String.format("[Action(%s) t=%d k=%d %d-%d]", m, t, k, start, end);
  }
}