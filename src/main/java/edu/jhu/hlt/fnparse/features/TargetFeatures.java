package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface TargetFeatures {

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
	 * return features that describe whether this frame is likely 
	 * evoked by this word in the sentence
	 */
	public FeatureVector getFeatures(Frame f, int targetIdx, Sentence s);
	
	/**
	 * maximum number of non-zero indexes returned by vectors from getFeatures
	 * (i.e. all indices in those vectors must be less than this)
	 */
	public int cardinality();
}

