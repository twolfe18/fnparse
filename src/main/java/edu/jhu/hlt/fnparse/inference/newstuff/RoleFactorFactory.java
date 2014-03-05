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
import edu.jhu.hlt.fnparse.features.HasRoleFeatures;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;

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
			
			VarConfig conf = this.getVars().getVarConfig(config);
			Frame f_i = frameVar.getFrame(conf);
			Frame r_ijk = roleVar.getFrame(conf);
			int k = roleVar.getRoleIdx();
			
			// f_i == nullFrame  =>  we don't care what r_ijk is (decoder will never get to r_ijk)
			// r_ijk == nullFrame  =>  role is not realized, which is allowed for any f_i
			if(r_ijk != Frame.nullFrame && f_i != Frame.nullFrame && r_ijk != f_i)
				return logDomain ? Double.NEGATIVE_INFINITY : 0d;
			
			if(k >= f_i.numRoles() && f_i != Frame.nullFrame)
				return logDomain ? Double.NEGATIVE_INFINITY : 0d;
			
			return super.getDotProd(config, model, logDomain);
		}
		
		@Override
		public FeatureVector getFeatures(int config) {
			if(cache[config] == null) {
				VarConfig conf = this.getVars().getVarConfig(config);
				Frame frame = frameVar.getFrame(conf);
				boolean roleActive = roleVar.argIsRealized(conf);
				Span argument = roleVar.getSpanDummy(conf);
				cache[config] = features.getFeatures(frame, frameVar.getTargetHeadIdx(),
						roleActive, roleVar.getRoleIdx(), argument, roleVar.getArgHeadIdx(), sent);
			}
			return cache[config];
		}
	}
	
}
