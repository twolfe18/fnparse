package edu.jhu.hlt.fnparse.features;

import java.util.List;

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
 * FC = frame head + expansion (full trigger/target)
 * R = role head
 * C = constituent or span
 * L = (dependency) link
 * 
 * TODO I goofed by mixing up E and C. We should merge them.
 * If we run the model with latent syntax, we will use these
 * span-features in conjunction with the constituency variables.
 * e.g. features: (Frame, Span) => FeatureVector
 * then instantiate an "FC" factor for latent syntax by using a
 * ternary factor on (f_i, f_i^e, c_jk) s.t. j <= i < k
 * 
 * fudge...
 * how to connect frame trigger/targets to constituency variables?
 * f_i^e takes on Expansion values, so it would need to touch every
 * c_jk that would correspond to an expansion about i; which is
 * very high treewidth...
 * there is something going on where f_i^e is one-hot, and there
 * might be an efficient way to do this, like Exactly1, but for
 * now its much easier to just ignore it. the large number of
 * prototypes should wash out this effect.
 * 
 * 
 * Use S for "span" instead of E or C, which both refer to the
 * variable type, not the type signature of the features that we need.
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
	
	public static interface FPE extends Features {
		public FeatureVector getFeatures(Frame f, Span target, FrameInstance prototype, Sentence s);
	}
	
	public static interface FR extends Features {
		public FeatureVector getFeatures(Frame f, boolean argIsRealized, int targetHeadIdx, int roleIdx, int argHeadIdx, Sentence s);
	}
	
	public static interface FRE extends Features {
		public FeatureVector getFeatures(Frame f, boolean argIsRealized, int targetHeadIdx, int roleIdx, Span argument, Sentence s);
	}

	/**
	 * if given f == Frame.nullFrame, this feature should return a FeatureVector with exactly one
	 * feature one (nullFrame feature), which never fires for f != Frame.nullFrame.
	 */
	public static interface FE extends Features {
		public FeatureVector getFeatures(Frame f, Span trigger, Sentence s);
	}
	
	public static interface E extends Features {
		public FeatureVector getFeatures(Span constituent, Sentence s);
	}
	
	public static interface FRL extends Features {
		public FeatureVector getFeatures(Frame f, boolean argIsRealized, boolean linkFromTargetHeadToArgHead, int targetHeadIdx, int roleIdx, int argHeadIdx, Sentence s);
	}
}
