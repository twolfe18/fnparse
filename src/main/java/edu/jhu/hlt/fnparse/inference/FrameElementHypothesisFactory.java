package edu.jhu.hlt.fnparse.inference;

import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface FrameElementHypothesisFactory {
	
	public String getName();

	public FrameElementHypothesis make(FrameHypothesis frameHyp, int roleIdx, Sentence s);
}
