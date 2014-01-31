package edu.jhu.hlt.fnparse.inference.variables;

import edu.jhu.gm.model.Var;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * Wrapper around a Frame variable f_i which ranges over a set of Frames.
 * @author travis
 */
public interface FrameHypothesis {
	
	/**
	 * @return variable that says what frame this target might evoke
	 */
	public Var getVar();
	
	/**
	 * The span that evokes the Frame in question.
	 */
	public Span getTargetSpan();
	
	/**
	 * The sentence that this hypothetical frame appears in.
	 */
	public Sentence getSentence();
	
	/**
	 * The Frame that corresponds to the i^th configuration of this variable.
	 */
	public Frame getPossibleFrame(int i);
	
	/**
	 * cardinality of the Frame variable that this wraps.
	 */
	public int numPossibleFrames();
	
	/**
	 * return the local index (i.e. less than maxRoles, not frame.getId)
	 * of the gold frame, or null if it is not known.
	 */
	public Integer getGoldFrameIndex();
	
	/**
	 * getPossibleFrame(getGoldFrameIndex()) == getGoldFrame
	 * is null if gold frame is not known.
	 */
	public Frame getGoldFrame();
	
	// convenience method for computing the gold labels for FrameElementHypothesis
	public FrameInstance getGoldFrameInstance();
	
	/**
	 * how many r_ij variables do we need
	 * (only need to take the max over numPossibleFrames)
	 */
	public int maxRoles();
}
