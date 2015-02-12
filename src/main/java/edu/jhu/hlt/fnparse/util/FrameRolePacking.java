package edu.jhu.hlt.fnparse.util;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;

/**
 * Used to get a dense non-negative integer representing (frame,role).
 *
 * @author travis
 */
public class FrameRolePacking {

  private int specialValues;  // TODO can be used to store more special values per frame
  private int[] roleOffsets;
  private int size;

  public FrameRolePacking() {
    this.specialValues = 1;
    int n = FrameIndex.framesInFrameNet + 1;
    roleOffsets = new int[n];
    size = 0;
    for (Frame f : FrameIndex.getInstance().allFrames()) {
      roleOffsets[f.getId()] = size;
      size += f.numRoles() + specialValues;
    }
  }

  public int index(Frame f) {
    return roleOffsets[f.getId()] + f.numRoles();
  }

  public int index(Frame f, int k) {
    assert k < f.numRoles();
    return roleOffsets[f.getId()] + k;
  }

  public int size() {
    return size;
  }
}
