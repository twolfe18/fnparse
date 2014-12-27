package edu.jhu.hlt.fnparse.rl;

import edu.jhu.hlt.fnparse.datatypes.Span;

public class ASpan {
  // Special values for the start position which have non-standard semantics
  public static final int ENTIRE_SENTENCE = -1;
  public static final int LEFT_OF = -2;
  public static final int RIGHT_OF = -3;
  public static final int STARTING_AT = -4;
  public static final int ENDING_AT = -5;
  public static final int CROSSING = -6;
  public static final int NULL_SPAN = -7;

  public final int start, end;

  public ASpan(int start, int end) {
    this.start = start;
    this.end = end;
  }
  
  public String toString() {
    if (isNormalSpan())
      return start + "-" + end;
    if (start == NULL_SPAN)
      return "NULL_SPAN";
    throw new RuntimeException("implement me");
  }

  public int width() {
    assert isNormalSpan();
    return end - start;
  }

  public boolean isNormalSpan() {
    return start >= 0;
  }

  public boolean isNullSpan() {
    return start == NULL_SPAN;
  }

  public double fractionOfSentence(int n) {
    if (start >= 0)
      return ((double) width()) / n;
    else if (start == ENTIRE_SENTENCE)
      return 1d;
    else if (start == LEFT_OF)
      return ((double) end) / n;
    else
      throw new RuntimeException("implement me");
  }

  public Span getSpan() {
    assert start >= 0;
    return Span.getSpan(start, end);
  }
}