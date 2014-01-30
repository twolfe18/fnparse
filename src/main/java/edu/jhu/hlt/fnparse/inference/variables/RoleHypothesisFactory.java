package edu.jhu.hlt.fnparse.inference.variables;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface RoleHypothesisFactory {

	/**
	 * give some description that can be printed to explain what the model is doing.
	 */
	public String getName();
	
	/**
	 * Returns many RoleHypothesis (binary variables representing where this role
	 * might be realized). A simple strategy would be to return a RoleHypothsis for
	 * every span in the sentence, whereas a more aggressive strategy might be to
	 * only return spans that are constituents in a gold parse.   
	 */
	public List<RoleHypothesis> make(FrameHypothesis frameHyp, int roleIdx, Sentence s);

}

