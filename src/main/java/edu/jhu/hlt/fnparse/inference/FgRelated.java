package edu.jhu.hlt.fnparse.inference;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.VarConfig;

public interface FgRelated {
	
	/**
	 * add variables, factors, or both
	 */
	public void register(FactorGraph fg, VarConfig gold);

}
