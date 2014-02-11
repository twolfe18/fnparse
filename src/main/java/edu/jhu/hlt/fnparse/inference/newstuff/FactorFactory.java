package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.List;

import edu.jhu.gm.model.Factor;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface FactorFactory {
	
	/**
	 * Make some factors.
	 * 
	 * You can build up a factor that is as costly as you want it to be
	 * given these variables.
	 * 
	 * The variables given should correspond to a frame target head word,
	 * such that r.parent == f.
	 */
	public List<Factor> initFactorsFor(Sentence s, FrameVar[] f, RoleVars[][][] r);
	
	// TODO add constituency/dependency parse variables to the signature above
}
