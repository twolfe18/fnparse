package edu.jhu.hlt.fnparse.datatypes;

import java.util.Arrays;

import edu.jhu.hlt.fnparse.util.Describe;

/**
 * You can use this to represent the target annotation as well, just set role=-1
 * and argument=null.
 * 
 * @author travis
 */
public class FrameArgInstance extends FrameRoleInstance {
  private Span argument;

  public FrameArgInstance(Frame f, Span t, int k, Span a) {
    super(f, t, k);
    this.argument = a;
    if (role < 0 && a != null) {
      throw new IllegalArgumentException(
          "Use role=-1 and argument=null for targets");
    }
  }

  @Override
  public int hashCode() {
    int a = argument == null ? 2111 : argument.hashCode();
    return super.hashCode() * 223 + a;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof FrameArgInstance) {
      FrameArgInstance fai = (FrameArgInstance) other;
      if (!super.equals(fai)) return false;
      if (argument == null)
        return fai.argument == null;
      else
        return argument.equals(fai.argument);
    }
    return false;
  }

  @Override
  public int compareTo(FrameRoleInstance other) {
    if (other instanceof FrameArgInstance) {
      FrameArgInstance fai = (FrameArgInstance) other;
      int c1 = super.compareTo(fai);
      if (c1 != 0) return c1;
      if (argument == null) {
        assert fai.argument == null;
        return 0;
      } else {
        return argument.compareTo(fai.argument);
      }
    } else {
      throw new IllegalStateException();
    }
  }

  public String describeAsFrameInstance(Sentence sent) {
    FrameInstance fi;
    if (role < 0) {
      fi = FrameInstance.frameMention(frame, target, sent);
    } else {
      Span[] arguments = new Span[frame.numRoles()];
      Arrays.fill(arguments, Span.nullSpan);
      arguments[role] = argument;
      fi = FrameInstance.newFrameInstance(
          frame, target, arguments, sent);
    }
    return Describe.frameInstance(fi);
  }
}
