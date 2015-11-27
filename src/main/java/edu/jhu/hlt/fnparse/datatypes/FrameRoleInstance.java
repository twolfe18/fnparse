package edu.jhu.hlt.fnparse.datatypes;

import edu.jhu.hlt.tutils.Hash;
import edu.jhu.hlt.tutils.Span;

/**
 * Represents a role to be filled in for a particular FrameInstance.
 *
 * @author travis
 */
public class FrameRoleInstance implements Comparable<FrameRoleInstance> {
  public final Frame frame;
  public final Span target;
  public final int role;

  public FrameRoleInstance(Frame frame, Span target, int role) {
    this.frame = frame;
    this.target = target;
    this.role = role;
  }

  @Override
  public int hashCode() {
    int f = frame == null ? 41 : frame.getId();
    int t = target == null ? 37 : target.hashCode();
    return Hash.mix(Hash.mix(f, role), t);
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof FrameRoleInstance) {
      FrameRoleInstance fri = (FrameRoleInstance) other;
      return role == fri.role
          && (frame == fri.frame || (frame != null && frame.equals(fri.frame)))
          && (target == fri.target || (target != null && target.equals(fri.target)));
    }
    return false;
  }

  /** Only really defined if target is not null */
  @Override
  public int compareTo(FrameRoleInstance arg0) {
    int c1 = frame.getName().compareTo(arg0.frame.getName());
    if (c1 != 0) return c1;
    int c2 = target.compareTo(arg0.target);
    if (c2 != 0) return c2;
    return role - arg0.role;
  }
}
