package edu.jhu.hlt.fnparse.features;

import java.io.Serializable;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.ProjDepTreeFactor.LinkVar;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.util.Alphabet;

/**
 * Implements feature extraction related to a binary factor connected to
 * binary variables. There are two ways that you would want to do this:
 * 1) fully parameterized: conjoin the cell (i.e. configuration for both vars) with the observed features
 * 2) Ising model: only let observed features fire for both vars set to 1
 * 3) modified Ising model: make observed features fire with weights * -1 for the (0,0) configuration
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
	
	private final Mode mode;
	private final ObservedFeatures features;
	private final Refinements isingRefs = new Refinements("bothOn");
	
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

	public Mode getMode() { return mode; }
	
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
			bothOn = features.getObservedFeatures(isingRefs);
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
	
	/**
	 * This returns a factor with just N features, where N is the number of
	 * configurations that f_it can take on. If l_ij is off, this factor has a
	 * score of 0, and otherwise has the score of the feature corresponding to
	 * "l_ij=true and f_it=Some_Frame".
	 * I am worried that the factors above, which are parameterized on all kinds
	 * of observed features of f_it have way too many features (even though
	 * Matt said this was successful for his SRL code).
	 */
	public static ExplicitExpFamFactor simpleBinaryFactor(
			LinkVar l_ij,
			Var f_it,
			Frame t,
			Alphabet<String> featureNames) {
		ExplicitExpFamFactor phi = new ExplicitExpFamFactor(
				new VarSet(l_ij, f_it));
		final FeatureVector empty = new FeatureVector();
		int n = phi.getVars().calcNumConfigs();
		assert n == 2 * f_it.getNumStates();
		for (int c = 0; c < n; c++) {
			VarConfig conf = phi.getVars().getVarConfig(c);
			if (conf.getState(l_ij) == LinkVar.TRUE &&
					conf.getState(f_it) == BinaryVarUtil.boolToConfig(true)) {
				FeatureVector fv = new FeatureVector();
				String fn = "l_{root,i}=true_f_{i," + t.getName() + "}=true";
				int idx = featureNames.lookupIndex(fn, true);
				fv.add(idx, 1d);
				phi.setFeatures(c, fv);
			} else {
				phi.setFeatures(c, empty);
			}
		}
		return phi;
	}
}
