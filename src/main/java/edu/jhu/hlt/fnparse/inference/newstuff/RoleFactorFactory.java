package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.features.HasRoleFeatures;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;
import edu.jhu.util.Alphabet;

/**
 * all features that look at a role variable should be housed here.
 * instantiate factors that concatenate features rather than have multiple
 * factors because this is more efficient for BP.
 * 
 * this should enforce all hard constraints on variables related to r_ijk.
 * 
 * @author travis
 */
public final class RoleFactorFactory extends HasRoleFeatures implements FactorFactory {
	
	public RoleFactorFactory(ParserParams params) {
		super(params);
	}
	
	@Override
	public List<Factor> initFactorsFor(Sentence s, FrameVar[] f, RoleVars[][][] r, ProjDepTreeFactor l) {
		if(this.hasNoFeatures())
			return Collections.emptyList();
		List<Factor> factors = new ArrayList<Factor>();
		int n = s.size();
		for(int i=0; i<n; i++) {
			if(r[i] == null) continue;
			for(int j=0; j<n; j++)
				for(int k=0; k<r[i][j].length; k++)
					factors.add(new F(f[i], r[i][j][k], s, this));
		}
		return factors;
	}
	
	static final class F extends ExpFamFactor {

		private static final long serialVersionUID = 1L;
		
		private Sentence sent;
		private HasRoleFeatures features;
		private FrameVar frameVar;
		private RoleVars roleVar;
		
		private FeatureVector[] cache;
		
		public F(FrameVar f_i, RoleVars r_ijk, Sentence sent, HasRoleFeatures features) {
			super(new VarSet(f_i.getFrameVar(), r_ijk.getRoleVar()));
			this.sent = sent;
			this.features = features;
			this.frameVar = f_i;
			this.roleVar = r_ijk;
			
			cache = new FeatureVector[getVars().calcNumConfigs()];
		}
		
		@Override
		public double getDotProd(int config, FgModel model, boolean logDomain) {
			return ruledOutByHardFactor(config) && !messedUpSituation(config)
				? (logDomain ? Double.NEGATIVE_INFINITY : 0d)
				: super.getDotProd(config, model, logDomain);
		}
		
		private boolean ruledOutByHardFactor(int config) {
			final VarConfig conf = this.getVars().getVarConfig(config);
			final Frame f_i = frameVar.getFrame(conf);
			final Frame r_ijk = roleVar.getFrame(conf);
			final int k = roleVar.getRoleIdx();

			// r_ijk can only weight in on f_i values for two kinds of frames:
			// 1. f_i = nullFrame (i.e. no argument is realized for the other frames)
			// 2. f_i = a frame that has at least k roles
			
			// the semantics of r_ijk are:
			// 1. r_ijk = nullFrame means all frames that r_ijk can speak about do not have an arg realized
			// 2. r_ijk = a frame with at least k roles, meaning that this arg is realized (f_i must match r_ijk)
			
			
			// we are making a big mistake here.
			// we are saying that for values of f_i that a particular r_ijk cannot weigh in on
			// (due to frame.numRoles < k)
			// that the factor takes probability 0 (for the entire column of the (f_i, r_ijk) factor!!!)
			
			// this is the hackiest thing ever...
			
			// i have to send *some* message in this case k >= frame.numRoles
			// so what should it be?
			
			// what i'd really like to do is send a message that would get added to the log-scores
			// rather than multiplied in. the multiplying in of 0 is what's screwing me up.
			// if we could add in to the log-score we could essentially say how much "evidence"
			// we want to contribute.
			
			// there is also the possibility that we could set these to observed variables and make
			// them predict r_ijk=nullFrame in cases where k >= frame.numRoles
			// ^^^ this is weird because we're presuming a role that doesn't exist (k for frame),
			//     exists and isn't realized.
			
			
			// ...
			
			// wait, i'm looking at the factor:
//			edge    = FgEdge [id=1094, Factor[r_{3,6,31},f_3] --> Var[f_3]]
//					factor  = FgNode [isVar=false, var=null, factor=Factor [
//					r_{3,6,31}  f_3  |  value
//					    0    0  |  1.000000
//					    1    0  |  0.000000
//					    0    1  |  0.000000
//					    1    1  |  0.000000
//					    0    2  |  1.000000
//					    1    2  |  0.250813
//					    0    3  |  0.000000
//					    1    3  |  0.000000
//					]]
//					message = Factor [
//					  f_3  |  value
//					    0  |  0.444284
//					    1  |  0.000000
//					    2  |  0.555716
//					    3  |  0.000000
//					]
			// why don't we allow (f_i=1, r_ijk=0),
			// meaning that a non-existent role for f_i=1 is not realized?
			// ...thats why, it presumes that there are non-existent roles
			
			
			boolean allowToWeighIn = k < f_i.numRoles() || f_i == Frame.nullFrame;
			boolean legalConfig = allowToWeighIn && (r_ijk == Frame.nullFrame || r_ijk == f_i);
			return !legalConfig;
		}

		public boolean messedUpSituation(int configIdx) {
			VarConfig conf = this.getVars().getVarConfig(configIdx);
			return messedUpSituation(frameVar.getFrame(conf));
		}
		public boolean messedUpSituation(Frame f_i) {
			int k = roleVar.getRoleIdx();
			return f_i != Frame.nullFrame && k >= f_i.numRoles();
		}
		
		// screw it, i'm going to abuse features and try to make them overcome the bias
		// of my model...
		// in the (f_i, r_ijk) factor, when we are in a case where f_i != nullFrame
		// and k >= f_i.numRoles, i'm going to have a feature fire to give some non-zero
		// mass so that the marginals for f_i s.t. k >= f_i.numRoles comes out to something >0
		// i want this feature to have and intercept an a feature that knows how many f_i
		// r_ijk can weight in on vs how many f_i there are total.
		public FeatureVector getFeaturesForMessedUpSituation(Frame f_i, Frame r_ijk) {
			assert roleVar.getPossibleFrames().size() < frameVar.getFrames().size();
			FeatureVector v = new FeatureVector();
			Alphabet<String> a = features.getFeatureAlph();
			v.add(a.lookupIndex("non-existent-role", true), 1d);
			// * f_i
			// * domain(f_i).size * domain(r_ijk).size
			// * (domain(f_i).size - domain(r_ijk).size)
			return v;
		}
		
		@Override
		public FeatureVector getFeatures(int config) {
			if(cache[config] == null) {
				VarConfig conf = this.getVars().getVarConfig(config);
				Frame frame = frameVar.getFrame(conf);
				boolean roleActive = roleVar.argIsRealized(conf);
				Span argument = roleVar.getSpanDummy(conf);
				if(messedUpSituation(frame)) {
					cache[config] = getFeaturesForMessedUpSituation(frame, roleVar.getFrame(conf));
				}
				else {
					if(ruledOutByHardFactor(config)) {
						cache[config] = AbstractFeatures.emptyFeatures;
					}
					else {
						cache[config] = features.getFeatures(frame, frameVar.getTargetHeadIdx(), roleActive,
								roleVar.getRoleIdx(), argument, roleVar.getArgHeadIdx(), sent);
					}
				}
			}
			return cache[config];
		}
	}
	
}
