package edu.jhu.hlt.fnparse.rl;

import edu.jhu.hlt.fnparse.datatypes.Span;

public class ASpan {
  /**
   * Special values for the start position which have non-standard semantics.
   *
   * For pruning, the semantics are clear (each of these is a standin for
   * pruning many spans). For COMMIT items, its less clear. One option is that
   * a COMMIT for a non-atomic span triggers a second step which chooses the
   * final span. One options is that you can only COMMIT to atomic spans.
   * Another option (which I'm implementing now) is that these other spans are
   * not allowed yet.
   *
   * Really these are just equivalent to features on spans (e.g. for some roles
   * we might learn a weight on the indicator for 'left of target' to be -inf,
   * or near it), but it is possible that they can free the model up to learn
   * sharper rules. If the model can rule out a possibility at step i, then it
   * does not need to try to fight itself at step i+1. Say the step-wise model
   * is correct (e.g. it really is a good decision to rule out all candidates to
   * the left of the target for a particular (frame,role)), then the model no
   * longer has any incentive to encourage other weights to help explain this
   * phenomenon. If the assumption is wrong, then the later weights can focus
   * all their weight on getting the remaining prediction problems right. Also,
   * if the assumption is partially right, then the step-wise model might either
   * learn that its not "right enough" and not make a hard pruning decision,
   * meaning that it reverts more or less to the original model, or it can take
   * that hit in recall and focus on a smaller discriminative task.
   */
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