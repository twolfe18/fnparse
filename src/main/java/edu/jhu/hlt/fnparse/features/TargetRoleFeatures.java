package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public interface TargetRoleFeatures {

	/**
	 * Describes what this feature does at a high level
	 */
	public String getDescription();
	
	/**
	 * says what this index specifically does
	 * (matches indexes in Vectors returned by getFeature)
	 */
	public String getFeatureName(int featIdx);
	
	/**
	 * return features that describe whether this frame at this targetIdx
	 * is likely to have its roleIdx^{th} role filled by span.
	 */
	public FeatureVector getFeatures(Frame f, Span span, int targetIdx, int roleIdx, Sentence sent);
	
	/**
	 * maximum number of non-zero indexes returned by vectors from getFeatures
	 * (i.e. all indices in those vectors must be less than this)
	 */
	public int cardinality();
	
}
