package edu.jhu.hlt.fnparse.util;

import edu.jhu.gm.model.FgModel;

public interface HasFgModel {

	public FgModel getWeights();
	
	/** needed to match the arguments to ExpFamFact.updateFromModel */
	public boolean logDomain();
	
}
