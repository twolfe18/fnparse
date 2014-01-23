package edu.jhu.hlt.fnparse.inference;

import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.Var;
import edu.jhu.hlt.fnparse.datatypes.Frame;
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
	 * @return factor responsible for scoring Frames
	 */
	public Factor getUnaryFactor();
	
	/**
	 * The span that evokes the Frame in question.
	 */
	public Span getTargetSpan();
	
	/**
	 * get the Frame that corresponds to the i^th configuration of this variable.
	 */
	public Frame getPossibleFrame(int i);
	
	/**
	 * cardinality of the Frame variable that this wraps.
	 */
	public int numPossibleFrames();
	
	/**
	 * how many r_ij variables do we need
	 * (only need to take the max over numPossibleFrames)
	 */
	public int maxRoles();
}