package edu.jhu.hlt.fnparse.features.precompute.featureselection;

import java.util.Arrays;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph;
import edu.jhu.hlt.tutils.hash.Hash;

/**
 * Previously passing around templates:String[], now we need to add on frame(role)
 * restrictions too, use this.
 *
 * @author travis
 */
public class FeatureName {
  public String[] templateStr;
  public int[] templateInt;
  public final int hash;

  public FeatureName(String[] templates) {
    if (templates == null || templates.length == 0)
      throw new IllegalArgumentException();
    this.templateStr = templates;
    this.hash = Hash.mixHashcodes(templates);
  }

  public FeatureName computeTemplateInts(BiAlph b) {
    templateInt = new int[templateStr.length];
    for (int i = 0; i < templateInt.length; i++)
      templateInt[i] = b.mapTemplate(templateStr[i]);
    Arrays.sort(templateInt);
    return this;
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof FeatureName) {
      FeatureName fn = (FeatureName) other;
      return Arrays.equals(templateInt, fn.templateInt);
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("<FeatureName templates=%s>", Arrays.toString(templateStr));
  }
}
