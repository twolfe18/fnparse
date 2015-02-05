package edu.jhu.hlt.fnparse.util;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;

/**
 * Used to get a dense integer representing (frame,role).
 *
 * @author travis
 */
public class FrameRolePacking {

  private int specialValues;  // TODO can be used to store more special values per frame
  private int[] roleOffsets;

  public FrameRolePacking() {
    this.specialValues = 1;
    int n = FrameIndex.framesInFrameNet + 1;
    roleOffsets = new int[n];
    int offset = 0;
    for (Frame f : FrameIndex.getInstance().allFrames()) {
      roleOffsets[f.getId()] = offset;
      offset += f.numRoles() + specialValues;
    }
  }

  public int index(Frame f) {
    return roleOffsets[f.getId()] + f.numRoles();
  }

  public int index(Frame f, int k) {
    assert k < f.numRoles();
    return roleOffsets[f.getId()] + k;
  }
}
