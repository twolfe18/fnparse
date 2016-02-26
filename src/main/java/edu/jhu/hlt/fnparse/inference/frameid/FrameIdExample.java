package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.Serializable;
import java.util.List;
import java.util.Random;

public class FrameIdExample implements Serializable {
  private static final long serialVersionUID = 4633106939313414511L;

  public final int frame;
  public final int[] targetFeatures;
  public boolean[] targetFeaturesDropout; // may be null, co-indexed with targetFeatures
  private int[] frameConfusionSet;  // sub-set of all frames allowable for this target

  public FrameIdExample(int frame, int[] tf) {
    this.frame = frame;
    this.targetFeatures = tf;
  }

  /** Assign a new dropout vector with 50% probability on every dimension */
  public void newDropout(Random r) {
    if (targetFeaturesDropout == null)
      targetFeaturesDropout = new boolean[targetFeatures.length];
    for (int i = 0; i < targetFeaturesDropout.length; i++)
      targetFeaturesDropout[i] = r.nextBoolean();
  }

  /** returs whether the i^th feature should be dropped out */
  public boolean dropout(int i) {
    assert i >= 0 && i < targetFeatures.length;
    return targetFeaturesDropout != null && targetFeaturesDropout[i];
  }

  /**
   * Provide a sub-set of all frames which may be considered in prediction
   * @param addGoldIfNotPresent is whether to add this.frame to the confusion
   * set if it is not in the given set of frames.
   */
  public void setFrameConfusionSet(List<Integer> frames, boolean addGoldIfNotPresent) {
    if (frames == null)
      throw new IllegalArgumentException();
    boolean addGold = addGoldIfNotPresent && !frames.contains(frame);
    frameConfusionSet = new int[frames.size() + (addGold ? 1 : 0)];
    for (int i = 0; i < frames.size(); i++)
      frameConfusionSet[i] = frames.get(i);
    if (addGold)
      frameConfusionSet[frames.size()] = frame;
  }

//  public int[] getConfusionSet(int[] defaultIfNoConfusionSetPresent) {
//    if (frameConfusionSet == null)
//      return defaultIfNoConfusionSetPresent;
//    return frameConfusionSet;
//  }
  public int[] getConfusionSet() {
    return frameConfusionSet;
  }

  /**
   * returns true if the confusion set has been set and the gold frame is not
   * present in it.
   */
  public boolean confusionSetFailure() {
    if (frameConfusionSet == null)
      return false;
    for (int f : frameConfusionSet)
      if (f == frame)
        return false;
    return true;
  }
}