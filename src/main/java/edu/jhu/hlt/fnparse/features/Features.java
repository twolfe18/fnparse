package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * F = frame head
 * FE = frame head + extent
 * FR = frame head + role
 * FP = frame head + prototype
 * R = role head
 * C = constituent or span
 * L = (dependency) link
 */
public interface Features {
		
	public static interface F { public FeatureVector getFeatures(Frame f, int targetHeadIdx, Sentence s); }
	
	public static interface FP { public FeatureVector getFeatures(Frame f, int targetHeadIdx, FrameInstance prototype, Sentence s); }
	
	public static interface FR { public FeatureVector getFeatures(Frame f, boolean argIsRealized, int targetHeadIdx, int roleIdx, int argHeadIdx, Sentence s); }
	
	public static interface FRE { public FeatureVector getFeatures(Frame f, boolean argIsRealized, int targetHeadIdx, int roleIdx, Span argument, Sentence s); }

	public static interface FC { public FeatureVector getFeatures(Frame f, Span trigger, Sentence s); }
	
	public static interface C { public FeatureVector getFeatures(Span constituent, Sentence s); }
	
	public static interface FRL { public FeatureVector getFeatures(Frame f, boolean argIsRealized, boolean linkFromTargetHeadToArgHead, int targetHeadIdx, int roleIdx, int argHeadIdx, Sentence s); }
}
