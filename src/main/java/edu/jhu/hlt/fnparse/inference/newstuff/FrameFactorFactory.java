package edu.jhu.hlt.fnparse.inference.newstuff;

import java.io.Serializable;
import java.util.*;

import edu.jhu.gm.feat.*;
import edu.jhu.gm.model.*;
import edu.jhu.hlt.fnparse.datatypes.*;
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
			
			// TODO new code path (expansion features are going away)
			if(fpeFeatures == null && eFeatures == null) {
				// now the only question is whether p is in the mix
				if(fpFeatures != null)
					containsF.add(fv.getPrototypeVar());
				factors.add(new F(fv, this, s, containsF));
			}
			
			else {
				System.err.println("WARNING: FrameVar is no longer using an Expansion var! Turn these features off.");
				assert false;
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
					
					factors.add(new F(fv, this, s, containsF));
					factors.add(new F(fv, this, s, containsE));
				}
			}
			
		}
		return factors;
	}

	/**
	 * with regards to feature extraction/caching, I know that the data that
	 * this factor points to never changes (i.e. frameVar, sent), so caching
	 * features should be safe (there is no cache invalidation aside from this
	 * object being garbage collected).
	 */
	static final class F extends ExpFamFactor implements Serializable {	// is the actual factor
		
		private static final long serialVersionUID = 1L;
		
		private transient HasFrameFeatures features;
		private transient FrameVar frameVar;
		private transient Sentence sent;
		private transient boolean readP, readF, readE;

		// this is the only thing we need to serialize
		// TODO need to just check this, not any other vars when we read stuff in
		private FeatureVector[] cache;

		public F(FrameVar fv, HasFrameFeatures features, Sentence sent, VarSet varsNeeded) {
			//super(new VarSet(fv.getPrototypeVar(), fv.getFrameVar(), fv.getExpansionVar()));
			super(varsNeeded);
			this.frameVar = fv;
			this.sent = sent;
			this.features = features;
			readP = varsNeeded.contains(fv.getPrototypeVar());
			readF = varsNeeded.contains(fv.getFrameVar());
			readE = false; //varsNeeded.contains(fv.getExpansionVar());

			int n = getVars().calcNumConfigs();
			cache = new FeatureVector[n];
		}
		
		public void setFeatures(HasFrameFeatures features) {
			this.features.setFeatures(features);
		}
		
		@Override
		public double getDotProd(int config, FgModel model, boolean logDomain) {
			
			VarConfig conf = this.getVars().getVarConfig(config);
			FrameInstance prototype = readP ? frameVar.getPrototype(conf) : null;
			Frame frame = readF ? frameVar.getFrame(conf) : null;
			
			// if it is one of the special prototypes, let it pass through
			boolean hasNoRealFrame = prototype instanceof FrameInstance.Prototype;
			if(readP && readF && !hasNoRealFrame && !prototype.getFrame().equals(frame))
				return logDomain ? Double.NEGATIVE_INFINITY : 0d;
			
			else return super.getDotProd(config, model, logDomain);
		}
		
		@Override
		public FeatureVector getFeatures(int config) {

			assert config < cache.length;
			FeatureVector fv = cache[config];
			if(fv != null) return fv;
			
			VarConfig conf = this.getVars().getVarConfig(config);
			FrameInstance prototype = readP ? frameVar.getPrototype(conf) : null;
			Frame frame = readF ? frameVar.getFrame(conf) : null;
			
			// if it is one of the special prototypes, let it pass through
			boolean hasNoRealFrame = prototype instanceof FrameInstance.Prototype;
			if(readP && readF && !hasNoRealFrame && !prototype.getFrame().equals(frame))
				return AbstractFeatures.emptyFeatures;	// gradient calls this, no params associated with this constraint.
			
			Span target = readE ? frameVar.getTarget(conf) : null;
			return features.getFeatures(frame, prototype, frameVar.getTargetHeadIdx(), target, sent);
		}
		
	}
}

