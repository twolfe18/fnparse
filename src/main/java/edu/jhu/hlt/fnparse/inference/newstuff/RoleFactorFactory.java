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
public class RoleFactorFactory extends HasRoleFeatures implements FactorFactory {
	
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
			if(f[i] == null) continue;
			for(int j=0; j<n; j++)
				for(int k=0; k<r[i][j].length; k++)
					factors.add(new F(f[i], r[i][j][k], s, this));
		}
		return factors;
	}
	
	static class F extends ExpFamFactor {

		private static final long serialVersionUID = 1L;
		
		private Sentence sent;
		private HasRoleFeatures features;
		private FrameVar frameVar;
		private RoleVars roleVar;
		
		public F(FrameVar f_i, RoleVars r_ijk, Sentence sent, HasRoleFeatures features) {
			super(new VarSet(f_i.getFrameVar(), r_ijk.getRoleVar()));
			this.sent = sent;
			this.features = features;
			this.frameVar = f_i;
			this.roleVar = r_ijk;
		}
		
		@Override
		public double getDotProd(int config, FgModel model, boolean logDomain) {
			
			VarConfig conf = this.getVars().getVarConfig(config);
			Frame frame = frameVar.getFrame(conf);
			boolean roleActive = roleVar.getRoleActive(conf);
			
			if(roleActive && frame == Frame.nullFrame)
				return logDomain ? Double.NEGATIVE_INFINITY : 0d;
			
			if(roleActive && roleVar.getRoleIdx() >= frame.numRoles())
				return logDomain ? Double.NEGATIVE_INFINITY : 0d;
			
			else return super.getDotProd(config, model, logDomain);
		}
		
		@Override
		public FeatureVector getFeatures(int config) {
			VarConfig conf = this.getVars().getVarConfig(config);
			Frame frame = frameVar.getFrame(conf);
			boolean roleActive = roleVar.getRoleActive(conf);
			Span argument = roleVar.getSpan(conf);
			return features.getFeatures(frame, frameVar.getTargetHeadIdx(),
					roleActive, roleVar.getRoleIdx(), argument, roleVar.getHeadIdx(), sent);
		}
	}
	
}
