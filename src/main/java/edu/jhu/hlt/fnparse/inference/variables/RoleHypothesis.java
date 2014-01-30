package edu.jhu.hlt.fnparse.inference.variables;

import edu.jhu.gm.model.Var;
import edu.jhu.hlt.fnparse.datatypes.Span;

public interface RoleHypothesis {
	
	static enum Label {
		
		UNK { public int getInt() { return -1; } },
		TRUE { public int getInt() { return 1; } },
		FALSE { public int getInt() { return 0; } };
		
		/**
		 * variable indices for these binary variables
		 */
		public abstract int getInt();
	}


	/**
	 * the factor graph binary variable for this hypothesis.
	 */
	public Var getVar();
	
	/**
	 * the index of the role that this frame serves
	 * (i.e. corresponds to getParent().getRole(_))
	 */
	public int getRoleIdx();
	
	/**
	 * where is this role located in the text?
	 */
	public Span getExtent();
	
	/**
	 * does this span really fill the role?
	 * throws exception if there is no gold label.
	 */
	public Label getGoldLabel();
}
