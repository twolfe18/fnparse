package edu.jhu.hlt.fnparse.inference.factors;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.FeExpFamFactor;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.variables.FrameHypothesis;

/**
 * Module for Frame factors and their features
 * @author travis
 */
public class FrameFactor extends FeExpFamFactor {

	public static interface Features {

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
		public FeatureVector getFeatures(Frame f, Span extent, Sentence s);

		/**
		 * maximum number of non-zero indexes returned by vectors from getFeatures
		 * (i.e. all indices in those vectors must be less than this)
		 */
		public int cardinality();
	}

	public static class FeatureExtractor implements edu.jhu.gm.feat.FeatureExtractor {
		
		private Features features;
		
		public FeatureExtractor(Features feats) {
			this.features = feats;
		}
		
		@Override
		public void init(FgExample ex) {}
		
		@Override
		public FeatureVector calcFeatureVector(FeExpFamFactor factor, int configId) {
			FrameHypothesis f_i = ((FrameFactor) factor).getFrameHyp();
			Frame f = f_i.getPossibleFrame(configId);
			Span extent = f_i.getTargetSpan();
			Sentence s = f_i.getSentence();
			return features.getFeatures(f, extent, s);
		}
	}

	// ======================== ACTUAL FACTOR CODE ===========================
	
	private static final long serialVersionUID = 1L;

	private FrameHypothesis frameHyp;

	public FrameFactor(FrameHypothesis frameHyp, FeatureExtractor fe) {
		super(new VarSet(frameHyp.getVar()), fe);
		this.frameHyp = frameHyp;
	}

	public FrameHypothesis getFrameHyp() { return frameHyp; }

}
