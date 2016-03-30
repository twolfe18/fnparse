package edu.jhu.hlt.fnparse.features.precompute.featureselection;

import java.util.function.Function;

import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile.Line;

/**
 * Doesn't do any filtering (every instance is passed through, see {@link InformationGainProducts#featuresUnrestricted}),
 * but changes the labels from something like getRoles or getFrames (the wrapped function) to a binary
 * label for whether the returned value was the special "nullLabel". E.g. for roles,
 * getRoles() will return {-1} if that (targetSpan,argSpan) was not a part of a gold label.
 *
 * @author travis
 */
public class NullLabelGetY implements Function<FeatureFile.Line, int[]> {

  private Function<FeatureFile.Line, int[]> wrapped;
  private int nullValue;
  private int[] yes, no;
  public final String name;

  public NullLabelGetY(String name, Function<FeatureFile.Line, int[]> wrapped) {
    this.name = name;
    this.wrapped = wrapped;
    this.nullValue = InformationGain.ADD_ONE ? 0 : -1;
    this.yes = new int[] {1};
    this.no = new int[] {0};
  }

  @Override
  public int[] apply(Line t) {
    int[] a = wrapped.apply(t);
    if (a == null)
      return null;
    assert a.length == 1;
    return a[0] == nullValue ? yes : no;
  }

}
