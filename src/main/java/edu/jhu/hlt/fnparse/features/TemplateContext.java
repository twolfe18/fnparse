package edu.jhu.hlt.fnparse.features;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.Span;

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
  private static final boolean DEBUG = false;

  public static void showContext(TemplateContext ctx) {
    Sentence s = ctx.getSentence();
    Frame f = ctx.getFrame();
    System.out.println("[context] stage=" + (ctx == null ? "UNSET" : ctx.getStage()));
    System.out.println("[context] sentence=" + s);
    System.out.println("[context] frame=" + (f == null ? "UNSET" : f.getName()));
    System.out.println("[context] role=" + ctx.getRoleS());
    System.out.println("[context] target=" + desc(ctx.getTarget(), ctx));
    System.out.println("[context] targetHead=" + desc(ctx.getTargetHead(), ctx));
    System.out.println("[context] arg = " + desc(ctx.getArg(), ctx));
    System.out.println("[context] argHead=" + desc(ctx.getArgHead(), ctx));
    System.out.println("[context] span1=" + desc(ctx.getSpan1(), ctx));
    System.out.println("[context] span2=" + desc(ctx.getSpan2(), ctx));
    System.out.println("[context] head1=" + desc(ctx.getHead1(), ctx));
    System.out.println("[context] head2=" + desc(ctx.getHead2(), ctx));
  }

  public static String desc(int i, TemplateContext ctx) {
    if (i == TemplateContext.UNSET)
      return "UNSET";
    return ctx.getSentence().getWord(i) + " @ " + i;
  }

  public static String desc(Span s, TemplateContext ctx) {
    if (s == null)
      return "UNSET";
    return Describe.span(s, ctx.getSentence()) + " @ " + s.toString();
  }

  public static final int UNSET = -3;

  // Common
  private Sentence sentence;
  private Frame frame;

  // For frame id
  private Span target;
  private int targetHead;

  // For role id
//  private int role; // if set, must also set frame
  private String roleS;
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

//  /** See {@link RoleSequenceStage} */
//  private int role2;

  // TODO put constituency and dependency parses in here instead of in sentence?

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
//    role = UNSET;
    roleS = null;
    arg = null;
    argHead = UNSET;
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
//    role2 = UNSET;
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
  @SuppressWarnings("unused")
  public void setTarget(Span target) {
    if (DEBUG && sentence != null) {
      assert target.start >= 0;
      assert target.end <= sentence.size();
    }
    this.target = target;
  }
  public int getTargetHead() {
    return targetHead;
  }
  @SuppressWarnings("unused")
  public void setTargetHead(int head) {
    assert head == UNSET || target == null || target.includes(head) : "target=" + target + " head=" + head;
    if (DEBUG && sentence != null) {
      assert head >= 0;
      assert head < sentence.size();
    }
    this.targetHead = head;
  }

//  public int getRole() {
//    return role;
//  }
//  public void setRole(int role) {
//    if (DEBUG) {
//      assert frame != null : "set frame first";
//      assert role >= 0;
//      assert role < frame.numRoles();
//    }
//    this.role = role;
//  }
  public String getRoleS() {
    return roleS;
  }
  public void setRoleS(String roleS) {
    this.roleS = roleS;
  }

  public Span getArg() {
    return arg;
  }
  public void setArg(Span arg) {
    if (DEBUG) {
      assert sentence != null : "set sentence first";
      assert arg.start >= 0;
      assert arg.end <= sentence.size();
    }
    this.arg = arg;
  }

  public Span getSpan2() {
    return span2;
  }
  public void setSpan2(Span span2) {
    if (DEBUG) {
      assert sentence != null : "set sentence first";
      assert span2.start >= 0;
      assert span2.end <= sentence.size();
    }
    this.span2 = span2;
  }

  public Span getSpan1() {
    return span1;
  }
  public void setSpan1(Span span1) {
    if (DEBUG) {
      assert sentence != null : "set sentence first";
      assert span1.start >= 0;
      assert span1.end <= sentence.size();
    }
    this.span1 = span1;
  }

  public int getHead2() {
    return head2;
  }
  public void setHead2(int head2) {
    assert head2 >= 0 || head2 == UNSET;
    if (DEBUG) {
      assert sentence != null : "set sentence first";
      assert head2 >= 0;
      assert head2 < sentence.size();
    }
    this.head2 = head2;
  }

  public int getHead1() {
    return head1;
  }
  public void setHead1(int head1) {
    assert head1 >= 0 || head1 == UNSET;
    if (DEBUG) {
      assert sentence != null : "set sentence first";
      assert head1 >= 0;
      assert head1 < sentence.size();
    }
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
    if (DEBUG) {
      assert sentence != null : "set sentence first";
      assert argHead >= 0;
      assert argHead < sentence.size();
    }
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

//  public void setRole2(int role) {
//    this.role2 = role;
//  }
//  public int getRole2() {
//    return role2;
//  }
//
//  public String getRoleStrDebug() {
//    if (role == UNSET)
//      return "UNSET";
//    if (role == frame.numRoles())
//      return "NO_ROLE";
//    return frame.getRole(role);
//  }
//  public String getRole2StrDebug() {
//    if (role2 == UNSET)
//      return "UNSET";
//    if (role2 == frame.numRoles())
//      return "NO_ROLE";
//    return frame.getRole(role2);
//  }
}
