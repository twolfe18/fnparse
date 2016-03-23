package edu.jhu.hlt.fnparse.features.precompute.featureselection;

import java.util.function.Function;

import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile.Line;

/**
 * Wraps another Function but returns null if its frame/frameRole match is not made.
 */
public class FrameRoleFilter implements Function<FeatureFile.Line, int[]> {
  private int frame;
  private int role;
  private boolean addOne;
  private Function<FeatureFile.Line, int[]> wrapped;

  public FrameRoleFilter(Function<FeatureFile.Line, int[]> wrapped, boolean addOne, int frame) {
    this.wrapped = wrapped;
    this.addOne = addOne;
    this.frame = frame;
    this.role = -1;
  }

  public FrameRoleFilter(Function<FeatureFile.Line, int[]> wrapped, boolean addOne, int frame, int role) {
    this.wrapped = wrapped;
    this.addOne = addOne;
    this.frame = frame;
    this.role = role;
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
      if (!found)
        return null;
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
      if (!found)
        return null;
    }
    return y;
  }
}