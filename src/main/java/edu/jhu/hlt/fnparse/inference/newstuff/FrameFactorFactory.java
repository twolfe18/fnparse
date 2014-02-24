package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.features.HasFrameFeatures;

/**
 * all features that DON'T look at a role variable should be housed here.
 * instantiate factors that concatenate features rather than have multiple
 * factors because this is more efficient for BP.
 * 
 * this should enforce all hard constraints on variables related to f_i.
 * 
 * NOTE: if we don't want to incur the cost of looping over p_i,
 * we will just make p_i observed and fix it at a null FrameInstance
 * (features should gracefully handle this case provided train and
 *  test match)
 * 
 * @author travis
 */
public class FrameFactorFactory extends HasFrameFeatures implements FactorFactory {

	@Override
	public List<Factor> initFactorsFor(Sentence s, FrameVar[] f, RoleVars[][][] r, ProjDepTreeFactor l) {
		List<Factor> factors = new ArrayList<Factor>();
		int n = s.size();
		for(int i=0; i<n; i++) {
			if(f[i] == null) continue;
			factors.add(new F(f[i], this, s));
		}
		return factors;
	}
	
//	/**
//	 * looks at the features that are non-null and chooses the smallest
//	 * set of variables needed to cover those features (affects the
//	 * complexity/runtime of the factor).
//	 */
//	protected VarSet getVarSet(FrameVar fv) {
//		VarSet vs = new VarSet();
//		if(fpeFeatures != null || fpFeatures != null)
//			vs.add(fv.getPrototypeVar());
//		
//		// if these factor, we still need to add these vars, just not
//		// in one factor, but many
//		
//		
//		
//		return vs;
//	}

	static class F extends ExpFamFactor {	// is the actual factor
		
		private static final long serialVersionUID = 1L;
		
		private HasFrameFeatures features;
		private FrameVar frameVar;
		private Sentence sent;

		public F(FrameVar fv, HasFrameFeatures features, Sentence sent) {
			super(new VarSet(fv.getPrototypeVar(), fv.getFrameVar(), fv.getExpansionVar()));
			this.frameVar = fv;
			this.sent = sent;
			this.features = features;
		}
		
		public void setFeatures(HasFrameFeatures features) {
			this.features.setFeatures(features);
		}
		
		@Override
		public double getDotProd(int config, FgModel model, boolean logDomain) {
			
			VarConfig conf = this.getVars().getVarConfig(config);
			Frame f = frameVar.getFrame(conf);
			FrameInstance p = frameVar.getPrototype(conf);
			
			if(!p.getFrame().equals(f))
				return logDomain ? Double.NEGATIVE_INFINITY : 0d;
			
			else return super.getDotProd(config, model, logDomain);
		}
		
		@Override
		public FeatureVector getFeatures(int config) {
			
			VarConfig conf = this.getVars().getVarConfig(config);
			FrameInstance prototype = frameVar.getPrototype(conf);
			Frame frame = frameVar.getFrame(conf);
			
			// fix all entries in factor where prototype doesn't match
			// frame to 0 (no parameters needed there, shouldn't even sum over it).
			if(!prototype.getFrame().equals(frame))
				return AbstractFeatures.emptyFeatures;	// gradient calls this, no params associated with this constraint.
				//throw new RuntimeException("getDotProd should have returned before calling this!");
			
			Span target = frameVar.getTarget(conf);
			return features.getFeatures(frame, prototype, frameVar.getTargetHeadIdx(), target, sent);
		}
		
	}
}
