package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.features.*;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;
import edu.jhu.util.Alphabet;

/**
 * all features that look at a role variable should be housed here.
 * instantiate factors that concatenate features rather than have multiple
 * factors because this is more efficient for BP.
 * 
 * @author travis
 */
public final class RoleFactorFactory extends HasRoleFeatures implements FactorFactory {
	
	private static final long serialVersionUID = 1L;

	public RoleFactorFactory(ParserParams params) {
		super(params.featIdx);
	}
	
	@Override
	public List<Factor> initFactorsFor(Sentence s, FrameVar[] f, RoleVars[][][] r, ProjDepTreeFactor l) {
		
		if(this.hasNoFeatures())
			return Collections.emptyList();
		
		List<Factor> factors = new ArrayList<Factor>();
		int n = s.size();
		for(int i=0; i<n; i++) {
			FrameVar f_i = f[i];
			if(f_i == null || r == null || r[i] == null) continue;
			for(int j=0; j<n; j++) {
				for(int k=0; k<r[i][j].length; k++) {
					
					RoleVars r_ijk = r[i][j][k];
					if(r_ijk == null) continue;
					
					// containsR and containsE could in principle be the same set
					if(this.freFeatures != null)
						throw new UnsupportedOperationException("update this code to merge containsR and containsE");
					
					HasRoleFeatures rFeats = new HasRoleFeatures(this);
					rFeats.setFeatures((Features.RE) null);
					rFeats.setFeatures((Features.E) null);
					VarSet containsR = new VarSet();
					containsR.add(r_ijk.getRoleVar());
					if(this.frFeatures != null) {
						containsR.add(f_i.getFrameVar());
						rFeats.setFeatures(frFeatures);
					}
					else
						assert false : "no role features?";
					
					HasRoleFeatures eFeats = new HasRoleFeatures(this);
					eFeats.setFeatures((Features.FR) null); 
					VarSet containsE = new VarSet();
					containsE.add(r_ijk.getExpansionVar());
					if(this.reFeatures != null)
						containsE.add(r_ijk.getRoleVar());
					else
						assert this.eFeatures != null : "no expansion features?";
					
					factors.add(new F(f_i, r_ijk, containsR, s, rFeats));
					factors.add(new F(f_i, r_ijk, containsE, s, eFeats));
				}
			}
		}
		return factors;
	}
	
	static final class F extends ExpFamFactor {

		private static final long serialVersionUID = 1L;
		
		private transient Sentence sent;
		private transient HasRoleFeatures features;
		private transient FrameVar frameVar;
		private transient RoleVars roleVar;
		
		private transient boolean readF, readR, readE;
		
		// this is the only thing we need to serialize
		// TODO need to just check this, not any other vars when we read stuff in
		private FeatureVector[] cache;
		
		// TODO reintroduce this optimization later
		// note that i don't think it will make a huge difference because we're only computing features once
		// indices in the VarSet/int[] config corresponding to the variables
//		int f_i_idx, r_ijk_idx;
//		int[] config = {-1, -1};
		
		public F(FrameVar f_i, RoleVars r_ijk, VarSet varsNeeded, Sentence sent, HasRoleFeatures features) {
			//super(new VarSet(f_i.getFrameVar(), r_ijk.getRoleVar()));
			super(varsNeeded);
			this.sent = sent;
			this.features = features;
			this.frameVar = f_i;
			this.roleVar = r_ijk;
			
			readF = varsNeeded.contains(f_i.getFrameVar());
			readR = varsNeeded.contains(r_ijk.getRoleVar());
			readE = varsNeeded.contains(r_ijk.getExpansionVar());
			
			cache = new FeatureVector[getVars().calcNumConfigs()];
			
//			VarSet vs = getVars();
//			f_i_idx = vs.indexOf(frameVar.getFrameVar());
//			r_ijk_idx = vs.indexOf(roleVar.getRoleVar());
		}
		
		public int getRoleIdx() { return roleVar.getRoleIdx(); }
		
		public int getTargetHead() { return frameVar.getTargetHeadIdx(); }

				
		@Override
		public double getDotProd(int config, FgModel model, boolean logDomain) {
			return readF && ruledOutByHardFactor(config) && !messedUpSituation(config)
				? (logDomain ? Double.NEGATIVE_INFINITY : 0d)
				: super.getDotProd(config, model, logDomain);
		}
		

		private boolean ruledOutByHardFactor(int configIdx) {

			// if you get here, just make sure that you're not calling this code path
			// the readE only version of this factor doesn't need to check for this
			if(!readF) 
				throw new IllegalStateException("this code breaks if we're not reading F");

			VarConfig conf = this.getVars().getVarConfig(configIdx);
			final Frame f_i = readF ? frameVar.getFrame(conf) : null;
			final Frame r_ijk = roleVar.getFrame(conf);
			final int k = roleVar.getRoleIdx();
			
			// r_ijk can only weight in on f_i values for two kinds of frames:
			// 1. f_i = nullFrame (i.e. no argument is realized for the other frames)
			// 2. f_i = a frame that has at least k roles
			
			// the semantics of r_ijk are:
			// 1. r_ijk = nullFrame means all frames that r_ijk can speak about do not have an arg realized
			// 2. r_ijk = a frame with at least k roles, meaning that this arg is realized (f_i must match r_ijk)

			boolean allowToWeighIn = k < f_i.numRoles() || f_i == Frame.nullFrame;
			boolean legalConfig = allowToWeighIn && (r_ijk == Frame.nullFrame || r_ijk == f_i);
			return !legalConfig;
		}
		

		public boolean messedUpSituation(int configIdx) {

			// if you get here, just make sure that you're not calling this code path
			// the readE only version of this factor doesn't need to check for this
			if(!readF) 
				throw new IllegalStateException("this code breaks if we're not reading F");
			
			VarConfig conf = this.getVars().getVarConfig(configIdx);
			final Frame f_i = readF ? frameVar.getFrame(conf) : null;
			final int k = roleVar.getRoleIdx();
			
			//return f_i != Frame.nullFrame && k >= f_i.numRoles();
			return roleVar.getPossibleFrames().size() < frameVar.getFrames().size() &&  k >= f_i.numRoles();
		}
		
		// screw it, i'm going to abuse features and try to make them overcome the bias
		// of my model...
		// in the (f_i, r_ijk) factor, when we are in a case where f_i != nullFrame
		// and k >= f_i.numRoles, i'm going to have a feature fire to give some non-zero
		// mass so that the marginals for f_i s.t. k >= f_i.numRoles comes out to something >0
		// i want this feature to have and intercept an a feature that knows how many f_i
		// r_ijk can weight in on vs how many f_i there are total.
		public FeatureVector getFeaturesForMessedUpSituation(Frame f_i, Frame r_ijk) {
			assert readF;
			assert roleVar.getPossibleFrames().size() < frameVar.getFrames().size();
			FeatureVector v = new FeatureVector();
			Alphabet<String> a = features.getFeatureAlph();
			v.add(a.lookupIndex("non-existent-role", true), 1d);
			v.add(a.lookupIndex("non-existent-role-" + f_i.getName(), true), 1d);
			v.add(a.lookupIndex("non-existent-role-k=" + roleVar.getRoleIdx(), true), 1d);
			v.add(a.lookupIndex(String.format("non-existent-role-|f_i|=%d-|r_ijk|=%d",
					frameVar.getFrames().size(), roleVar.getPossibleFrames().size()), true), 1d);
			v.add(a.lookupIndex(String.format("non-existent-role-|f_i|-|r_ijk|=%d",
					frameVar.getFrames().size() - roleVar.getPossibleFrames().size()), true), 1d);
			return v;
		}
		
		
		@Override
		public FeatureVector getFeatures(int configIdx) {
			if(cache[configIdx] == null) {
				
				VarConfig conf = this.getVars().getVarConfig(configIdx);
				final Frame f_i = readF ? frameVar.getFrame(conf) : null;
				final Frame r_ijk = readR ? roleVar.getFrame(conf) : null;
				
				if(readF && messedUpSituation(configIdx))
					cache[configIdx] = getFeaturesForMessedUpSituation(f_i, r_ijk);
				else {
					if(readF && ruledOutByHardFactor(configIdx)) {
						cache[configIdx] = AbstractFeatures.emptyFeatures;
					}
					else {
						//assert readE;
						Span argument = readE ? roleVar.getSpan(conf) : null;
						cache[configIdx] = features.getFeatures(f_i, r_ijk, frameVar.getTargetHeadIdx(),
								roleVar.getRoleIdx(), argument, roleVar.getArgHeadIdx(), sent);
					}
				}
			}
			return cache[configIdx];
		}
	}
	
}
