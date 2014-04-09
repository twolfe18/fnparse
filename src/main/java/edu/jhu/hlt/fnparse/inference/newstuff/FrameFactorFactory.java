package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.*;

import edu.jhu.gm.feat.*;
import edu.jhu.gm.model.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance.Prototype;
import edu.jhu.hlt.fnparse.features.*;

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
public final class FrameFactorFactory extends HasFrameFeatures implements FactorFactory {

	private static final long serialVersionUID = 1L;
	
	@Override
	public String toString() { return "<FrameFactorFactory>"; }

	@Override
	public List<Factor> initFactorsFor(Sentence s, List<FrameInstanceHypothesis> fr, ProjDepTreeFactor l) {
		List<Factor> factors = new ArrayList<Factor>();
		for(FrameInstanceHypothesis fhyp : fr) {
			final int T = fhyp.numFrames();
			final int i = fhyp.getTargetHeadIdx();
			for(int t=0; t<T; t++) {
				Frame f = fhyp.getFrame(t);
				Prototype p = null;
				VarSet vs = new VarSet(fhyp.getFrameVar(t));
				FeatureVector features = getFeatures(f, p, i, s);
				factors.add(new FF(vs, features));
			}
		}
		return factors;
	}
	
	static final class FF extends ExpFamFactor {

		private static final long serialVersionUID = 1L;

		private FeatureVector features;

		public FF(VarSet vars, FeatureVector features) {
			super(vars);
			if(vars.size() != 1)
				throw new IllegalArgumentException("shouldn't this have just the frame var?");
			this.features = features;
		}

		@Override
		public FeatureVector getFeatures(int config) {
			if(BinaryVarUtil.configToBool(config))
				return features;
			return AbstractFeatures.emptyFeatures;
		}
		
	}

	/**
	 * features will only fire for an active representation of a variable
	 * @deprecated
	 */
	static final class F extends ExpFamFactor {
		
		private static final long serialVersionUID = 1L;
		
		private HasFrameFeatures features;
		private int targetHead;
		private Frame frame;
		private Sentence sent;
		private FeatureVector cache;

		public F(Frame f, int targetHead, HasFrameFeatures features, Sentence sent, VarSet varsNeeded) {
			super(varsNeeded);
			this.frame = f;
			this.targetHead = targetHead;
			this.sent = sent;
			this.features = features;
		}
		
		public void setFeatures(HasFrameFeatures features) {
			this.features.setFeatures(features);
		}
		
		/* i don't know why i would still need this...
		@Override
		public double getDotProd(int config, FgModel model, boolean logDomain) {
			
			VarConfig conf = this.getVars().getVarConfig(config);
			FrameInstance prototype = null;
			
			// if it is one of the special prototypes, let it pass through
			boolean hasNoRealFrame = prototype instanceof FrameInstance.Prototype;
			if(!hasNoRealFrame && !prototype.getFrame().equals(frame))
				return logDomain ? Double.NEGATIVE_INFINITY : 0d;
			
			else return super.getDotProd(config, model, logDomain);
		}
		*/
		
		@Override
		public FeatureVector getFeatures(int config) {
			boolean active = BinaryVarUtil.configToBool(config);
			if(active) {
				if(cache == null)
					cache = features.getFeatures(frame, null, targetHead, sent);
				return cache;
			}
			else return AbstractFeatures.emptyFeatures;
		}
		
	}
}

