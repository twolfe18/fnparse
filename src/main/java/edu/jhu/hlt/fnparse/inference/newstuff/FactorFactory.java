package edu.jhu.hlt.fnparse.inference.newstuff;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface FactorFactory extends FgRelated {

	
	/**
	 * Tells you what sentence you're factory is working on now.
	 * Calls to initFactorsFor() between calls to startSentence()
	 * and endSentence() will be combinations of variables in the sentence
	 * provided to startSentence().
	 */
	public void startSentence(Sentence s);
	
	
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

	
	/**
	 * register each of the factors that have been created with initFactorsFor()
	 */
	@Override
	public void register(FactorGraph fg, VarConfig gold);
	
	
	/**
	 * clear any collection of factors you've been holding onto between calls to
	 * initFactorsFor() and register().
	 */
	public void endSentence();
	
	
	// TODO add constituency/dependency parse variables to the signature above
}
