package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.Serializable;
import java.util.Random;

public class FrameIdExample implements Serializable {
  private static final long serialVersionUID = 4633106939313414511L;

  public final int frame;
  public final int[] targetFeatures;
  public boolean[] targetFeaturesDropout;

  public FrameIdExample(int frame, int[] tf) {
    this.frame = frame;
    this.targetFeatures = tf;
  }

  public void newDropout(Random r) {
    if (targetFeaturesDropout == null)
      targetFeaturesDropout = new boolean[targetFeatures.length];
    for (int i = 0; i < targetFeaturesDropout.length; i++)
      targetFeaturesDropout[i] = r.nextBoolean();
  }

  public boolean dropout(int i) {
    assert i >= 0 && i < targetFeatures.length;
    return targetFeaturesDropout != null && targetFeaturesDropout[i];
  }
}