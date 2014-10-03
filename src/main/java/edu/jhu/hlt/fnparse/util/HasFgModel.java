package edu.jhu.hlt.fnparse.util;

import edu.jhu.gm.model.FgModel;

public interface HasFgModel {

	public FgModel getWeights();

	public void setWeights(FgModel weights);

	/** needed to match the arguments to ExpFamFact.updateFromModel */
	public boolean logDomain();
}
