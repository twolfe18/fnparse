package edu.jhu.hlt.fnparse.features.precompute.featureselection;

import java.util.Arrays;
import java.util.function.Function;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;

/**
 * Previously passing around templates:String[], now we need to add on frame(role)
 * restrictions too, use this.
 *
 * @author travis
 */
public class FeatureName {
  public String[] templateStr;
  public int[] templateInt;
  public Function<FeatureFile.Line, int[]> getY; // may be InformationGain.GET_FRAMES/GET_ROLES or a FrameRoleFilter

  public FeatureName(String[] templates, Function<FeatureFile.Line, int[]> getY) {
    this.templateStr = templates;
    this.getY = getY;
  }

  public void computeTemplateInts(BiAlph b) {
    templateInt = new int[templateStr.length];
    for (int i = 0; i < templateInt.length; i++)
      templateInt[i] = b.mapTemplate(templateStr[i]);
    Arrays.sort(templateInt);
  }

  @Override
  public String toString() {
    return String.format("<FeatureName templates=%s %s>", Arrays.toString(templateInt), getY.toString());
  }
}
