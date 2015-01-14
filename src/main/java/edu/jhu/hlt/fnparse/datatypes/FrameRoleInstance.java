package edu.jhu.hlt.fnparse.datatypes;

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
    if (frame == null)
      throw new IllegalArgumentException();
    if (role >= frame.numRoles())
      throw new IllegalArgumentException();
    this.frame = frame;
    this.target = target;
    this.role = role;
  }

  @Override
  public int hashCode() {
    int h = (role << 11) ^ frame.getId();
    if (target != null)
      h ^= (target.hashCode16() << 16);
    return h;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof FrameRoleInstance) {
      FrameRoleInstance fri = (FrameRoleInstance) other;
      return role == fri.role
          && frame.equals(fri.frame)
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
