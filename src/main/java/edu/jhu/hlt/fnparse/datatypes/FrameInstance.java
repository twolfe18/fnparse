package edu.jhu.hlt.fnparse.datatypes;

import java.util.Arrays;
import java.util.Collection;

/**
 * This class should represent all details of the data
 * needed for inference and evaluation, but as little else as
 * possible so that we can add things (like priors over frames
 * in a document cluster) without this code noticing.
 * 
 * @author travis
 */
public class FrameInstance {

  protected Frame frame;
  protected Span target;  // index of the target word
  protected Sentence sentence;

  /**
   * indices correspond to frame.getRoles()
   * null-instantiated arguments should be Span.nullSpan, NOT null
   */
  protected Span[] arguments;

  protected FrameInstance(Frame frame, Span target, Span[] arguments, Sentence sent) {
    this.frame = frame;
    this.target = target; // targetIdx is the index of trigger token in the sentence.
    this.arguments = arguments;
    this.sentence = sent;
  }

  /**
   * Use this for frame mentions where the argument structure is known.
   * Roles that do not appear in the sentence should appear in the arguments array with value Span.nullSpan
   */
  public static FrameInstance newFrameInstance(Frame frame, Span target, Span[] arguments, Sentence sent) {
    if(frame == null || arguments == null || target == null || sent == null)
      throw new IllegalArgumentException();
    if(frame.numRoles() != arguments.length)
      throw new IllegalArgumentException("you haven't provided the correct number of arguments");
    for(int i=0; i<arguments.length; i++)
      if(arguments[i] == null)
        throw new IllegalArgumentException();
    return new FrameInstance(frame, target, arguments, sent);
  }

  /**
   * Use this for mentions of a frame where we do not know if the arguments are present or not
   * (i.e. we only have information on the frame and it's target span).
   * 
   * NOTE: this should only be used for decoders, not data providers. This is useful for the
   * frameId stage of decoding where you haven't yet predicted the arguments.
   */
  public static FrameInstance frameMention(Frame frame, Span target, Sentence sent) {
    if(frame == null || sent == null)
      throw new IllegalArgumentException();
    Span[] args = new Span[frame.numRoles()];
    Arrays.fill(args, Span.nullSpan);
    return new FrameInstance(frame, target, args, sent);
  }

  public FrameInstance clone() {
    Span[] args = this.arguments.clone();
    FrameInstance fi = new FrameInstance(
        this.frame, this.target, args, this.sentence);
    return fi;
  }

  public boolean onlyTargetLabeled() { return this.arguments == null; }

  public Span getTarget() { return target; }

  public Sentence getSentence() { return sentence; }

  public Frame getFrame() { return frame; }

  public Span getArgument(int roleIdx) { return arguments[roleIdx]; }

  public int numArguments() { return arguments.length; }

  public Span[] getArguments() { return arguments; }

  public int numRealizedArguments() {
    int c = 0;
    for(Span a : arguments) {
      assert a != null;
      if(a != Span.nullSpan) c++;
    }
    return c;
  }

  public String[] getArgumentTokens(int roleIdx) {
    return sentence.getWordFor(arguments[roleIdx]);
  }

  public void setArgument(int roleIdx, Span extent) {
    arguments[roleIdx] = extent;
  }
  
  public void getRealizedArgs(Collection<Span> addTo) {
    for (int k = 0; k < arguments.length; k++)
      if (arguments[k] != Span.nullSpan)
        addTo.add(arguments[k]);
  }

  @Override
  public String toString() {
    return String.format("<FrInst %s @ %s with %d args>", frame.getName(), target, numRealizedArguments());
  }

  @Override
  public int hashCode() {
    return (frame.hashCode() << 20) ^ (target.hashCode() << 10) ^ sentence.hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if(other instanceof FrameInstance) {
      FrameInstance fi = (FrameInstance) other;
      return target == fi.target
          && frame.equals(fi.frame)
          && sentence.equals(fi.sentence)
          && Arrays.equals(arguments, fi.arguments);
    } else {
      return false;
    }
  }
}
