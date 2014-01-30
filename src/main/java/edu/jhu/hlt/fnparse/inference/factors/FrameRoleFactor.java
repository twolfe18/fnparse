package edu.jhu.hlt.fnparse.inference.factors;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.FeExpFamFactor;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.variables.FrameHypothesis;
import edu.jhu.hlt.fnparse.inference.variables.RoleHypothesis;

/**
 * Module for Frame-Role factors and their features
 * @author travis
 */
public class FrameRoleFactor extends FeExpFamFactor {
	
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
		 * return features that describe whether this frame at this targetIdx
		 * is likely to have its roleIdx^{th} role filled by span.
		 */
		public FeatureVector getFeatures(Frame f, Span argumentSpan, Span targetSpan, int roleIdx, Sentence sent);
		
		/**
		 * maximum number of non-zero indexes returned by vectors from getFeatures
		 * (i.e. all indices in those vectors must be less than this)
		 */
		public int cardinality();
	}

	public static class FeatureExtractor implements edu.jhu.gm.feat.FeatureExtractor {
		
		private static final FeatureVector emptyFeatures = new FeatureVector();
		
		private FrameRoleFactor.Features features;
	
		public FeatureExtractor(FrameRoleFactor.Features feats) {
			this.features = feats;
		}
		
		@Override
		public void init(FgExample ex) {}
		
		@Override
		public FeatureVector calcFeatureVector(FeExpFamFactor factor, int configId) {
			FrameRoleFactor frFactor = (FrameRoleFactor) factor;
			RoleHypothesis rh = frFactor.getRoleHype();
			VarConfig varConf = factor.getVars().getVarConfig(configId);
			int r_ij_value = varConf.getState(rh.getVar());
			if(r_ij_value == 0)
				return emptyFeatures;
			FrameHypothesis fh = frFactor.getFrameHyp();
			Sentence sent = fh.getSentence();
			Span targetSpan = fh.getTargetSpan();
			Span argSpan = rh.getExtent();
			int roleIdx = rh.getRoleIdx();
			int f_i_value = varConf.getState(fh.getVar());
			Frame f = fh.getPossibleFrame(f_i_value);
			return features.getFeatures(f, argSpan, targetSpan, roleIdx, sent);
		}
	}

	// ======================== ACTUAL FACTOR CODE ===========================
	
	private static final long serialVersionUID = 1L;

	private FrameHypothesis frameHyp;
	private RoleHypothesis roleHyp;

	public FrameRoleFactor(FrameHypothesis frameHyp, RoleHypothesis roleHyp, FeatureExtractor fe) {
		super(new VarSet(frameHyp.getVar(), roleHyp.getVar()), fe);
		this.frameHyp = frameHyp;
		this.roleHyp = roleHyp;
	}

	public FrameHypothesis getFrameHyp() { return frameHyp; }

	public RoleHypothesis getRoleHype() { return roleHyp; }
	
}
