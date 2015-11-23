package edu.jhu.hlt.fnparse.datatypes;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import edu.jhu.prim.tuple.Pair;

/**
 * This class should represent all details of the data needed for inference and
 * evaluation, but as little else as possible so that we can add things (like
 * priors over frames in a document cluster) without this code noticing.
 *
 * @author travis
 */
public class FrameInstance implements Serializable {
  private static final long serialVersionUID = 7449903277653964511L;

  protected Frame frame;
  protected Span target;
  protected Sentence sentence;

  /**
   * indices correspond to frame.getRoles()
   * null-instantiated arguments should be Span.nullSpan, NOT null
   */
  protected Span[] arguments;

  // Only applies to Propbank
  // Indices are the same as arguments, e.g. if index("ARG2") = 1, then
  // instances of "ARG2" will show up in arguments[1] and instances of
  // "C-ARG2" will show up in argumentContinuations[1] (ditto for ref roles).
  protected List<Span>[] argumentContinuations;
  protected List<Span>[] argumentReferences;

  protected FrameInstance(Frame frame, Span target, Span[] arguments, Sentence sent) {
    this.frame = frame;
    this.target = target; // targetIdx is the index of trigger token in the sentence.
    this.arguments = arguments;
    this.sentence = sent;
  }

  public static class PropbankDataException extends Exception {
    private static final long serialVersionUID = 1L;
    public final FrameInstance attempted;
    public PropbankDataException(String msg, FrameInstance fi) {
      super(msg);
      this.attempted = fi;
    }
  }

  public static class MissingRoleException extends PropbankDataException {
    private static final long serialVersionUID = 1L;
    public MissingRoleException(String msg, FrameInstance fi) {
      super(msg, fi);
    }
  }

  public static class DependentRoleException extends PropbankDataException {
    private static final long serialVersionUID = 1L;
    public DependentRoleException(String msg, FrameInstance fi) {
      super(msg, fi);
    }
  }

  public List<Span> getContinuationRoleSpans(int k) {
    return argumentContinuations[k];
  }
  public List<Span> getReferenceRoleSpans(int k) {
    return argumentReferences[k];
  }

  /**
   * Allows multiple spans per role.
   *
   * @param arguments should have {@link Span}s which are sentence relative.
   */
  @SuppressWarnings("unchecked")
  public static FrameInstance buildPropbankFrameInstance(
      Frame frame,
      Span target,
      List<Pair<String, Span>> arguments,
      Sentence sent) throws PropbankDataException {
    if (sent == null || frame == null || target == null || arguments == null)
      throw new IllegalArgumentException();
    int K = frame.numRoles();
    FrameInstance fi = new FrameInstance(frame, target, new Span[K], sent);
    for (int k = 0; k < K; k++)
      fi.arguments[k] = Span.nullSpan;
    fi.argumentContinuations = new List[K];
    fi.argumentReferences = new List[K];
    for (int k = 0; k < K; k++) {
      fi.argumentContinuations[k] = new ArrayList<>();
      fi.argumentReferences[k] = new ArrayList<>();
    }
    List<String> roles = Arrays.asList(frame.getRoles());
    for (Pair<String, Span> x : arguments) {
      Span arg = x.get2();
      if (arg.start >= sent.size() || arg.start < 0)
        throw new IllegalArgumentException();
      if (arg.end > sent.size() || arg.end < 1)
        throw new IllegalArgumentException();
      String roleName = x.get1();
      boolean r = false, c = false;
      if (roleName.startsWith("R-")) {
        r = true;
        roleName = roleName.substring(2);
      } else if (roleName.startsWith("C-")) {
        c = true;
        roleName = roleName.substring(2);
      }
      int k = roles.indexOf(roleName);
      if (k < 0)
        throw new MissingRoleException("unknown role: " + x + " not in " + roles + " frame=" + frame.getName(), fi);
      if (c) {
        fi.argumentContinuations[k].add(arg);
      } else if (r) {
        fi.argumentReferences[k].add(arg);
      } else {
        fi.arguments[k] = arg;
      }
    }
    // Sanity check
    for (int k = 0; k < K; k++) {
      String r = roles.get(k);
      if (fi.arguments[k] == Span.nullSpan && fi.argumentContinuations[k].size() > 0)
        throw new DependentRoleException("continuation of nothing: " + r, fi);
      if (fi.arguments[k] == Span.nullSpan && fi.argumentReferences[k].size() > 0)
        throw new DependentRoleException("reference of nothing: " + r, fi);
      if (fi.argumentReferences[k].size() > 1)
        throw new DependentRoleException("too many references: " + r, fi);
    }
    return fi;
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
