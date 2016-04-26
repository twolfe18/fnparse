package edu.jhu.hlt.fnparse.features.precompute.featureselection;

import java.util.function.Function;

import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile.Line;
import edu.jhu.hlt.tutils.Log;

/**
 * Wraps another Function but returns null if its frame/frameRole match is not made.
 */
public class FrameRoleFilter implements Function<FeatureFile.Line, int[]> {
  public static final boolean DEBUG = false;

  private int frame;
  private int role;
  private boolean addOne;
  private Function<FeatureFile.Line, int[]> wrapped;

  private String dbgFrame, dbgRole;

  @Override
  public String toString() {
//    return "<FrameRoleFilter f=" + frame + " r=" + role + " addOne=" + addOne + ">";
    return String.format("<FrameRoleFilter %s (%d) %s (%d) wrapped=%s>",
        dbgFrame, frame, dbgRole, role, wrapped.toString());
  }

  public String getRestrictionString(boolean useIntsInsteadOfStringsForFramesAndRoles) {
    assert frame >= 0;
    if (useIntsInsteadOfStringsForFramesAndRoles) {
      if (role >= 0)
        return "f=" + frame + ",r=" + role;
      return "f=" + frame;
    } else {
      assert dbgFrame != null;
      if (role >= 0) {
        assert dbgRole != null;
        return "f=" + dbgFrame + ",r=" + dbgRole;
      }
      return "f=" + dbgFrame;
    }
  }

  public FrameRoleFilter(Function<FeatureFile.Line, int[]> wrapped, boolean addOne, int frame, String dbgFrame) {
    assert frame >= 0;
    this.wrapped = wrapped;
    this.addOne = addOne;
    this.frame = frame;
    this.dbgFrame = dbgFrame;
    this.role = -1;
    this.dbgRole = "any";
  }

  public FrameRoleFilter(Function<FeatureFile.Line, int[]> wrapped, boolean addOne, int frame, String dbgFrame, int role, String dbgRole) {
    assert frame >= 0;
    assert role >= 0;
    this.wrapped = wrapped;
    this.addOne = addOne;
    this.frame = frame;
    this.dbgFrame = dbgFrame;
    this.role = role;
    this.dbgRole = dbgRole;
  }

  public boolean getAddOne() {
    return addOne;
  }

  public Function<FeatureFile.Line, int[]> getWrapped() {
    return wrapped;
  }

  public int getFrame() {
    return frame;
  }

  public int getRole() {
    return role;
  }

  public boolean hasRoleRestriction() {
    return role >= 0;
  }

  @Override
  public int[] apply(Line t) {
    int[] y = wrapped.apply(t);
    if (y == null)
      return null;
    if (frame >= 0) {
      boolean found = false;
      int[] fs = t.getFrames(addOne);
      for (int f : fs) {
        if (f == frame) {
          found = true;
          break;
        }
      }
      if (!found) {
        if (DEBUG)
          Log.info("failed frame filter: " + this.toString());
        return null;
      }
    }
    if (role >= 0) {
      boolean found = false;
      int[] ks = t.getRoles(found);
      for (int f : ks) {
        if (f == role) {
          found = true;
          break;
        }
      }
      if (!found) {
        if (DEBUG)
          Log.info("failed role filter: " + this.toString());
        return null;
      }
    }
    if (DEBUG)
      Log.info("passed: " + this.toString() + " line=" + t);
    return y;
  }
}