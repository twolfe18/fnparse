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
			FrameVar fv = f[i];
			if(fv == null) continue;
			
			// constraints: F and E must appear in at least one factor, P cannot appear on its own
			// FPE
			// FP, FE
			// FP, E
			// FE
			// F, E
			// => there will be either 1 or 2 factors
			
			VarSet containsF = new VarSet();
			containsF.add(fv.getFrameVar());
			if(fpeFeatures != null) {
				containsF.add(fv.getPrototypeVar());
				containsF.add(fv.getExpansionVar());
				factors.add(new F(fv, this, s, containsF));
			}
			else {	// there will be 2 factors
				
				VarSet containsE = new VarSet();
				containsE.add(fv.getExpansionVar());
				
				if(fpFeatures != null)
					containsF.add(fv.getPrototypeVar());
				if(feFeatures != null)
					containsE.add(fv.getFrameVar());
				
				factors.add(new F(fv, this, s, containsF));
				factors.add(new F(fv, this, s, containsE));
			}
		}
		return factors;
	}

	static class F extends ExpFamFactor {	// is the actual factor
		
		private static final long serialVersionUID = 1L;
		
		private HasFrameFeatures features;
		private FrameVar frameVar;
		private Sentence sent;
		private boolean readP, readF, readE;

		public F(FrameVar fv, HasFrameFeatures features, Sentence sent, VarSet varsNeeded) {
			//super(new VarSet(fv.getPrototypeVar(), fv.getFrameVar(), fv.getExpansionVar()));
			super(varsNeeded);
			this.frameVar = fv;
			this.sent = sent;
			this.features = features;
			readP = varsNeeded.contains(fv.getPrototypeVar());
			readF = varsNeeded.contains(fv.getFrameVar());
			readE = varsNeeded.contains(fv.getExpansionVar());
		}
		
		public void setFeatures(HasFrameFeatures features) {
			this.features.setFeatures(features);
		}
		
		@Override
		public double getDotProd(int config, FgModel model, boolean logDomain) {
			
			VarConfig conf = this.getVars().getVarConfig(config);
			FrameInstance prototype = readP ? frameVar.getPrototype(conf) : null;
			Frame frame = readF ? frameVar.getFrame(conf) : null;
			
			if(readP && readF && !prototype.getFrame().equals(frame))
				return logDomain ? Double.NEGATIVE_INFINITY : 0d;
			
			else return super.getDotProd(config, model, logDomain);
		}
		
		@Override
		public FeatureVector getFeatures(int config) {
			
			VarConfig conf = this.getVars().getVarConfig(config);
			FrameInstance prototype = readP ? frameVar.getPrototype(conf) : null;
			Frame frame = readF ? frameVar.getFrame(conf) : null;
			
			// fix all entries in factor where prototype doesn't match
			// frame to 0 (no parameters needed there, shouldn't even sum over it).
			if(readP && readF && !prototype.getFrame().equals(frame))
				return AbstractFeatures.emptyFeatures;	// gradient calls this, no params associated with this constraint.
				//throw new RuntimeException("getDotProd should have returned before calling this!");
			
			Span target = readE ? frameVar.getTarget(conf) : null;
			return features.getFeatures(frame, prototype, frameVar.getTargetHeadIdx(), target, sent);
		}
		
	}
}
