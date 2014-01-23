package edu.jhu.hlt.fnparse.inference;

import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * Implementations of this interface should handle frame pruning
 * given a target span.
 * @author travis
 */
public interface FrameHypothesisFactory {

	public String getName();
	
	/**
	 * called right after construction.
	 * @param targetSpan is the extent of the frame being evoked.
	 */
	public FrameHypothesis make(Span targetSpan, FrameInstance goldFrame, Sentence s);
	
}
