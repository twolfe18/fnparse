package edu.jhu.hlt.fnparse.features;

import java.io.Serializable;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;

/**
 * Implements feature extraction related to a binary factor connected to
 * binary variables. There are two ways that you would want to do this:
 * 1) fully parameterized: conjoin the cell (i.e. configuration for both vars) with the observed features
 * 2) Ising model: only let observed features fire for both vars set to 1
 * 3) modified Ising model: make observed features fire with weights * -1 for the (0,0) configuration
 * 
 * TODO i just noticed that my code is not really conducive to factoring this code out, i'm going to
 * have to hack this out (int FrameFactorFactory and RoleFactorFactory).
 * 
 * @author travis
 */
public class BinaryBinaryFactorHelper implements Serializable {
	
	private static final long serialVersionUID = 1L;


	public static enum Mode {
		FULLY_PARAMETERIZED,
		ISING,
		MOD_ISING,
		NONE
	}
	
	private Mode mode;
	private ObservedFeatures features;
	
	public BinaryBinaryFactorHelper(Mode m, ObservedFeatures features) {
		this.mode = m;
		this.features = features;
	}


	public static interface ObservedFeatures extends Serializable {
		/**
		 * partially apply all of the data needed to compute observed features
		 * in order to implement this function (i.e. use a closure).
		 */
		public abstract FeatureVector getObservedFeatures(Refinements r);
	}

	
	/**
	 * @return null if mode is NONE.
	 */
	public ExplicitExpFamFactor getFactor(VarSet vars) {
		
		assert vars.size() == 2;
		assert vars.get(0).getNumStates() == 2;
		assert vars.get(1).getNumStates() == 2;
		
		if(mode == Mode.NONE)
			return null;

		ExplicitExpFamFactor phi = new ExplicitExpFamFactor(vars);

		// compute the diagonals ahead of time
		FeatureVector bothOn = null, bothOff = null;
		if(mode != Mode.FULLY_PARAMETERIZED) {
			bothOn = features.getObservedFeatures(Refinements.noRefinements);
			bothOff = new FeatureVector(bothOn);
			bothOff.scale(-1d);
		}

		for(int i=0; i<4; i++) {
			int[] cfg = vars.getVarConfigAsArray(i);
			boolean x1 = BinaryVarUtil.configToBool(cfg[0]);
			boolean x2 = BinaryVarUtil.configToBool(cfg[1]);
			FeatureVector fv;
			if(mode == Mode.FULLY_PARAMETERIZED) {
				fv = features.getObservedFeatures(new Refinements("x1=" + x1 + ",x2=" + x2));
			}
			else if(mode == Mode.ISING) {
				if(x1 && x2) fv = bothOn;
				else fv = AbstractFeatures.emptyFeatures;
			}
			else {
				assert mode == Mode.MOD_ISING;
				if(x1 && x2) fv = bothOn;
				else if(!x1 && !x2) fv = bothOff;
				else fv = AbstractFeatures.emptyFeatures;
			}
			phi.setFeatures(i, fv);
		}
		return phi;
	}
}
