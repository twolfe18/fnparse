package edu.jhu.hlt.fnparse.features;

import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;

/**
 * The semantics of these has changed a bit since last time.
 * Information like the frame or role in question is now known statically
 * (because we are using binary variables, we know this information from the identity of the variable).
 * The letters are now only used to describe the signature of the method, how they are used is
 * left for the downstream user. 
 * 
 * @author travis
 */
public interface Features {
	
	public List<Integer> dontRegularize();
	
	public static interface F extends Features {
		public FeatureVector getFeatures(Frame f, int targetHeadIdx, Sentence s);
	}
	
	public static interface FP extends Features {
		public FeatureVector getFeatures(Frame f, int targetHeadIdx, FrameInstance prototype, Sentence s);
	}
	
	public static interface FR extends Features {
		public FeatureVector getFeatures(Frame f, int targetHeadIdx, int roleIdx, int argHeadIdx, Sentence s);
	}
	
	public static interface RE extends Features {
		public FeatureVector getFeatures(Frame f, int targetHeadIdx, int roleIdx, int argHeadIdx, Span argSpan, Sentence s);
	}
	
	public static interface E extends Features {
		public FeatureVector getFeatures(Span constituent, Sentence s);
	}
	
	public static interface FRL extends Features {
		public FeatureVector getFeatures(Frame f, boolean linkFromTargetHeadToArgHead, int targetHeadIdx, int roleIdx, int argHeadIdx, Sentence s);
	}
	
}
