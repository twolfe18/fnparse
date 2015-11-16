package edu.jhu.hlt.fnparse.util;

import java.util.Random;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.rand.ReservoirSample;

/**
 * Used to get a dense non-negative integer representing (frame,role).
 *
 * @author travis
 */
public class FrameRolePacking {

  private int specialValues;  // TODO can be used to store more special values per frame
  private int[] roleOffsets;
  private int size;

  public FrameRolePacking(FrameIndex fi) {
    this.specialValues = 1;
    int n = fi.getNumFrames();
    roleOffsets = new int[n];
    size = 0;
    for (Frame f : fi.allFrames()) {
      roleOffsets[f.getId()] = size;
      size += f.numRoles() + specialValues;
    }
  }

  public int getNumFrames() {
    return roleOffsets.length;
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

  public static void main(String[] args) {
    ExperimentProperties.init(args);
    Random rand = new Random(9001);
    FrameIndex propbank = FrameIndex.getPropbank();
    FrameRolePacking frp = new FrameRolePacking(propbank);
    for (int i = 0; i < 10000; i++) {
      Frame f = ReservoirSample.sampleOne(propbank.allFrames(), rand);
      assert f.getId() < propbank.getNumFrames();
      for (int j = 0; j < 100; j++) {
        int k = rand.nextInt(f.numRoles());
        int index = frp.index(f, k);
        System.out.println(f.getId() + "\t" + k + "\t" + index);
      }
    }
  }
}
