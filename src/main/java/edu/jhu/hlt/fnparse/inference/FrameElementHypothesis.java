package edu.jhu.hlt.fnparse.inference;

import edu.jhu.gm.model.Var;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * Wrapper around an r_ij (span-valued) variable.
 * @author travis
 */
public interface FrameElementHypothesis {
	
	/**
	 * Variable that says what span this frameElement is realized at.
	 */
	public Var getVar();
	
	/**
	 * homomorphic to the i in r_ij
	 */
	public Span getTargetSpan();
	
	/**
	 * the j in r_ij
	 */
	public int getRoleIdx();

	/**
	 * r_ij
	 */
	public Span getSpan(int i);
	
	/**
	 * cardinality of r_ij
	 */
	public int numSpans();	
}
