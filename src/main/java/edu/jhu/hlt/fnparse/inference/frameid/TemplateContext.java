package edu.jhu.hlt.fnparse.inference.frameid;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * All of the information needed for a template to make an extraction.
 * 
 * In CRF terms, this context includes both X and Y.
 * 
 * NOTE: For cases where Y is binary, you can control whether you want to have
 * features that fire for the Y=false case by clearing the state of this context
 * (meaning that features have no context to extract from, and thus wont fire).
 * You might want to do this if there are many more cases where Y=false in the
 * gold configuration to prune the number of features and prevent overfitting.
 */
public class TemplateContext {

  public static final int UNSET = -3;

  // Common
  private Sentence sentence;
  private Frame frame;

  // For frame id
  private Span target;
  private int targetHead;

  // For role id
  private int role; // if set, must also set frame
  private Span arg;
  private int argHead;

  // For generic features, who do not care about the specific semantics of a
  // variable type (e.g. if a span is a target, arg, or something else), but
  // just featurize a particular type.
  // 
  // NOTE: you can often mimic a specific feature that knows about the semantics
  // of a variable with a generic one in conjunction with another variable type
  // e.g. "frame * spanWidth" is the same as "targetSpanWidth" as long as frame
  // there is only one frame-valued and span-possessing variable being inferred
  // at a time.
  // Therefore, whenever possible, you should attempt to use generic variables
  // in your templates rather than specific variables (e.g. use span1 vs target
  // or arg).
  //
  // NOTE: you should populate these variables in order, e.g. span1 before span2
  private Span span1, span2;
  private int head1, head2;

  // Used for factors that touch a constituency tree variable.
  private int span1_isConstituent;

  // TODO fill these in (here and in factor factories)
  private int head1_isRoot;
  //private int head1_gov_head2;

  public TemplateContext() {
    clear();
  }

  public void clear() {
    sentence = null;
    frame = null;
    target = null;
    targetHead = UNSET;
    role = UNSET;
    arg = null;
    setArgHead(UNSET);
    span1 = null;
    span2 = null;
    head1 = UNSET;
    head2 = UNSET;
    span1_isConstituent = UNSET;
    head1_isRoot = UNSET;
    //head1_gov_head2 = UNSET;
  }

  public Sentence getSentence() {
    return sentence;
  }
  public void setSentence(Sentence sentence) {
    this.sentence = sentence;
  }
  public Frame getFrame() {
    return frame;
  }
  public void setFrame(Frame frame) {
    this.frame = frame;
  }
  public Span getTarget() {
    return target;
  }
  public void setTarget(Span target) {
    this.target = target;
  }
  public int getHead() {
    return targetHead;
  }
  public void setTargetHead(int head) {
    assert target == null || target.includes(head);
    this.targetHead = head;
  }

  public int getRole() {
    return role;
  }
  public void setRole(int role) {
    this.role = role;
  }
  public Span getArg() {
    return arg;
  }
  public void setArg(Span arg) {
    this.arg = arg;
  }

  public Span getSpan2() {
    return span2;
  }
  public void setSpan2(Span span2) {
    this.span2 = span2;
  }

  public Span getSpan1() {
    return span1;
  }

  public void setSpan1(Span span1) {
    this.span1 = span1;
  }

  public int getHead2() {
    return head2;
  }

  public void setHead2(int head2) {
    this.head2 = head2;
  }

  public int getHead1() {
    return head1;
  }

  public void setHead1(int head1) {
    this.head1 = head1;
  }

  public boolean getSpan1_isConstituent() {
    if (span1_isConstituent < 0)
      throw new IllegalStateException("not set");
    return span1_isConstituent != 0;
  }
  public void setSpan1_isConstituent(boolean span1_isConstituent) {
    this.span1_isConstituent = span1_isConstituent ? 1 : 0;
  }
  public void setSpan1_isntSet() {
    this.span1_isConstituent = UNSET;
  }

  public int getArgHead() {
    return argHead;
  }

  public void setArgHead(int argHead) {
    this.argHead = argHead;
  }

  public boolean getHead1_isRoot() {
    if (head1_isRoot < 0)
      throw new IllegalStateException("not set");
    return head1_isRoot != 0;
  }
  public void setHead1_isRoot(boolean head1_isRoot) {
    this.head1_isRoot = head1_isRoot ? 1 : 0;
  }
}
