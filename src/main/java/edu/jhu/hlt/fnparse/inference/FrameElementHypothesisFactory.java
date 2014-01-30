package edu.jhu.hlt.fnparse.inference;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.variables.FrameHypothesis;

/**
 * @author travis
 * @deprecated
 */
public interface FrameElementHypothesisFactory {
	
	public String getName();

	public FrameElementHypothesis make(FrameHypothesis frameHyp, int roleIdx, Sentence s);
}
