package edu.jhu.hlt.fnparse.inference.frameid;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.stages.Stage;

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
 * 
 * NOTE: There are not guards on getters. That is, if a value is not set, you
 * will get back whatever value is there. The values are chosen so that you
 * should hit a NPE or index out of bounds error, but the exception is boolean
 * valued variables. You must call the isSet method before calling them.
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
  private int span2_isConstituent;

  private int head1_parent;
  private int head2_parent;

  private int prune;

  // If you want to restrict some features to particular stages, you can write
  // a Template that filters on this variable
  private Class<? extends Stage<?, ?>> stage;

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
    span2_isConstituent = UNSET;
    head1_parent = UNSET;
    head2_parent = UNSET;
    stage = null;
    prune = UNSET;
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
  public int getTargetHead() {
    return targetHead;
  }
  public void setTargetHead(int head) {
    assert head == UNSET || target == null || target.includes(head);
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
    assert head2 >= 0 || head2 == UNSET;
    this.head2 = head2;
  }

  public int getHead1() {
    return head1;
  }

  public void setHead1(int head1) {
    assert head1 >= 0 || head1 == UNSET;
    this.head1 = head1;
  }

  public boolean getSpan1IsConstituent() {
    assert getSpan1IsConstituentIsSet();
    return span1_isConstituent != 0;
  }
  public boolean getSpan1IsConstituentIsSet() {
    return span1_isConstituent >= 0;
  }
  public void setSpan1IsConstituent(boolean span1_isConstituent) {
    this.span1_isConstituent = span1_isConstituent ? 1 : 0;
  }
  public void clearSpan1IsConstituent() {
    this.span1_isConstituent = UNSET;
  }

  public boolean getSpan2IsConstituent() {
    assert getSpan2IsConstituentIsSet();
    return span2_isConstituent != 0;
  }
  public boolean getSpan2IsConstituentIsSet() {
    return span2_isConstituent >= 0;
  }
  public void setSpan2IsConstituent(boolean span2_isConstituent) {
    this.span2_isConstituent = span2_isConstituent ? 2 : 0;
  }
  public void clearSpan2IsConstituent() {
    this.span2_isConstituent = UNSET;
  }

  public int getArgHead() {
    return argHead;
  }
  public void setArgHead(int argHead) {
    this.argHead = argHead;
  }

  public Class<? extends Stage<?, ?>> getStage() {
    return stage;
  }
  public void setStage(Class<? extends Stage<?, ?>> stage) {
    this.stage = stage;
  }

  public int getHead1_parent() {
    return head1_parent;
  }
  public void setHead1_parent(int head1_parent) {
    this.head1_parent = head1_parent;
  }

  public int getHead2_parent() {
    return head2_parent;
  }
  public void setHead2_parent(int head2_parent) {
    this.head2_parent = head2_parent;
  }

  public boolean isPrune() {
    assert isPruneSet();
    return prune > 0;
  }
  public boolean isPruneSet() {
    return prune != UNSET;
  }
  public void setPrune(boolean prune) {
    this.prune = prune ? 1 : 0;
  }
}
