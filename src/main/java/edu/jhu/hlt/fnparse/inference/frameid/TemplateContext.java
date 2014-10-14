package edu.jhu.hlt.fnparse.inference.frameid;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * All of the information needed for a template to make an extraction.
 */
public class TemplateContext {

  public static final int UNSET = -3;

  private Sentence sentence;
  private Frame frame = null;
  private Span target = null;
  private int targetHead = UNSET;

  private int role = UNSET;
  private Span arg = null;

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
  public void setHead(int head) {
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
}
