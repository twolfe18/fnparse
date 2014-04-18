package edu.jhu.hlt.fnparse.features;

import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;

/**
 * The semantics of these has changed a bit since last time.
 * Information like the frame or role in question is now known statically
 * (because we are using binary variables, we know this information from the identity of the variable).
 * The letters are now only used to describe the signature of the method, how they are used is
 * left for the downstream user. 
 * 
 * @author travis
 */
public interface Features {
	
	public List<Integer> dontRegularize();
	
	/*
	 * these features say nothing about where they are applied (i.e. what factor they belong to).
	 * the interface only specifies what information they featurize, and likely this will need
	 * to be conjoined with a particular configuration.
	 * 
	 * AH, TODO pass into each of these functions a "refinements" object that has string prefixes and weights.
	 * this will let the caller decide what these things are parameterizing without needing to duplicate the vector.
	 * The simplest example of using this is to conjoin with the variable configuration that the factor takes on.
	 */

	/** frame */
	public static interface F extends Features {
		public void featurize(FeatureVector v, Refinements r, int i, Frame t, Sentence s);
	}
	/** frame + link */
	public static interface FD extends Features {
		public void featurize(FeatureVector v, Refinements r, int i, Frame t, int l, Sentence s);
	}
	/** frame + role */
	public static interface R extends Features {
		public void featurize(FeatureVector v, Refinements r, int i, Frame t, int j, int k, Sentence s);
	}
	/** frame + role + link */
	public static interface RD extends Features {
		public void featurize(FeatureVector v, Refinements r, int i, Frame t, int j, int k, int l, Sentence s);
	}
	/** frame + role + expansion */
	public static interface RE extends Features {
		public void featurize(FeatureVector v, Refinements r, int i, Frame t, int j, int k, Span arg, Sentence s);
	}

}
