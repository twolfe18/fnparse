package edu.jhu.hlt.fnparse.inference.newstuff;

public interface FactorFactory extends FgRelated {

	/**
	 * Make some factors and hold onto them.
	 * You add them to the model when FgRelated.register is called.
	 * 
	 * You can build up a factor that is as costly as you want it to be
	 * given these variables.
	 * 
	 * The variables given should correspond to a frame target head word,
	 * such that r.parent == f.
	 */
	public void initFactorsFor(FrameVar f, RoleVars r);
	
	// TODO add constituency/dependency parse variables to the signature above
	
}
