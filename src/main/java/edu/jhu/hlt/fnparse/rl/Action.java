package edu.jhu.hlt.fnparse.rl;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * represents a constraint on the set of arguments a particular
 * (frame,target,role) can take on.
 * 
 * TODO generalize this further, but for now I want to get it working with
 * only COMMIT actions (i.e. choosing a span -- including nullSpan -- for a 
 * frame-role).
 */
public class Action {
  public static final int COMMIT = 0;   // use the span value of aspan
  //public static final int KEEP = 1;     // exclude all spans in aspan
  //public static final int PRUNE = 2;    // include all spans in aspan

  public int t;          // (frame,target)
  public int k;          // role
  public int mode;
  public ASpan aspan;

  public Action(int t, int k, int mode, ASpan aspan) {
    this.t = t;
    this.k = k;
    this.mode = mode;
    this.aspan = aspan;
  }

  public boolean includes(Span s) {
    assert aspan.start >= 0 : "add special semantics versions";
    return aspan.start <= s.start && aspan.end >= s.end;
  }

  public boolean matches(Span s) {
    return s.start == aspan.end && s.end == s.end;
  }

  public String toString() {
    String m = mode == COMMIT ? "COMMIT" : "???";
    return String.format("[Action(%s) t=%d k=%d %s]", m, t, k, aspan);
  }

  public String toString(State s) {
    FrameInstance fi = s.getFrameInstance(t);
    Frame f = fi.getFrame();
    String m = mode == COMMIT ? "COMMIT" : "???";
    return String.format("[Action(%s) frame=%s@%d role=%s: %s]",
        m, f.getName(), fi.getTarget().start, f.getRole(k), aspan);
  }
}