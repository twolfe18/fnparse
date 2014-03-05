package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.*;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.*;

/**
 * reasons to keep r_ijk distinct from r_ijk^e:
 * - l_ij variables will want to talk to on j variable, rather than many r_ijk vars (where j indexes spans instead of heads)
 * 
 * 
 * Proposal:
 * let domain(r_ijk) = {null} union {expansions}
 * psi(f_i, r_ijk, l_ij) fires 1 if(f_i != nullFrame && r_ijk != null && l_ij == 1)
 *   => this is now a bigger loop because it must go over all expansions rather than just binary for r_ijk
 *   
 * The reason I wanted to do this is so that I could get marginal probs for (r_ijk,r_ijk^e) during decoding
 * (this term is needed to determine the predictions/loss)
 * 
 * Exactly1 only works in a collection of one-hot binary variables...
 * actually i'm pretty sure this could be generalized to k-ary variables where one of k is "null"
 * 
 * where did i want to use Exactly1?
 * a given role can only show up once...
 * Exactly1(r_i*k) \forall i,k
 * 
 * 
 * Siblings:
 * Its common to see that an arg is a sibling to its frame.
 * How to encode:
 * phi(f_i, r_ijk, l_pi, l_pj) \forall p
 * 
 * =============================================================================================
 * march 4
 * ok, we're going to go another direction with these variables.
 * r_ijk and r_ijk^e are no longer fused, and r_ijk is changing.
 * instead of r_ijk \in {0, 1} for off/on, r_ijk will take on the
 * same values as f_i, except we'll call nullFrame "off".
 * 
 * there will be a hard factor enforcing:
 *   f_i=X, r_ijk=Y, X!=Y, X!=nullFrame => 0 probability
 * 
 * also, r_ijk s.t. f_i=nullFrame will be considered latent.
 * 
 * the expansion variable is back.
 * 
 * @author travis
 */
public class RoleVars implements FgRelated {
	
	public static final int maxArgRoleExpandLeft = 10;
	public static final int maxArgRoleExpandRight = 3;
		
	// meta information
	private int targetHeadIdx;	// aka "i"
	private int argHeadIdx;		// aka "j", head of the argument span (target head comes from parent)
	private int roleIdx;		// aka "k"
	
	// if(training) 
	//   boolean latent = f_i == Frame.nullFrame;
	// else if(doingFrameDecode)
	//   boolean latent = true;
	// else
	//   boolean prune = f_i == Frame.nullFrame;	// not latent or predicted!
	private VarType varType;
	
	// primary var
	private List<Frame> possibleFrames;
	private Var headVar;			// same domain as f_i, but the index for nullFrame means "off" -- or arg not realized
	private int headVarGold = -1;

	// TODO add the expansion stuff back
	// expansion related
//	private Expansion.Iter expansions;
//	private Var expansionVar;
//	private int expansionVarGold = -1;
	
	@Override
	public String toString() {
		return String.format("<r_{i=%d, j=%d, k=%d} domain={%d frames} varType=%s>",
				targetHeadIdx, argHeadIdx, roleIdx, possibleFrames.size(), varType);
	}
	
	public RoleVars(VarType latentOrPredicted, List<Frame> possibleFrames, Sentence s,
			int targetHeadIdx, int argHeadIdx, int roleIdx, boolean logDomain) {
		
		// we need to prune down the number of frames based on roleIdx
		this.possibleFrames = new ArrayList<Frame>();
		this.possibleFrames.add(Frame.nullFrame);	// represents "arg is not realized"
		for(Frame f : possibleFrames)
			if(roleIdx < f.numRoles() && f != Frame.nullFrame)
				this.possibleFrames.add(f);

		if(this.possibleFrames.size() < 2)
			throw new IllegalArgumentException();
		
		this.targetHeadIdx = targetHeadIdx;
		this.argHeadIdx = argHeadIdx;
		this.roleIdx = roleIdx;
		
		this.varType = latentOrPredicted;
		
		String headVarName = String.format("r_{%d,%d,%d}", targetHeadIdx, argHeadIdx, roleIdx);
		this.headVar = new Var(latentOrPredicted, this.possibleFrames.size(), headVarName, null);
		
//		this.expansions = new Expansion.Iter(headIdx, s.size(), maxArgRoleExpandLeft, maxArgRoleExpandRight);
//		String expVarName = String.format("r^e_{%d,%d,%d}", parent.getTargetHeadIdx(), headIdx, roleIdx);
//		this.expansionVar = new Var(VarType.PREDICTED, this.expansions.size(), expVarName, null);
	}
	
	/**
	 * use this to say that this argument was not instantiated.
	 */
	public void setGoldIsNull() {
		headVarGold = possibleFrames.indexOf(Frame.nullFrame);
		if(headVarGold < 0) throw new IllegalStateException();
		
		// TODO expansions
	}
	
	/**
	 * use this to say that this argument was realized in the sentence.
	 */
	public void setGold(Frame f, Span s) {
		if(s == Span.nullSpan)
			throw new IllegalArgumentException("use setGoldIsNull()");
		if(f == Frame.nullFrame)
			throw new IllegalArgumentException();
		
		headVarGold = possibleFrames.indexOf(f);
		if(headVarGold < 0) throw new IllegalStateException();
		
		// TODO expansions
	}
	
	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		fg.addVar(headVar);
		if(varType == VarType.PREDICTED)
			gold.put(headVar, headVarGold);
	}
	
	/** i */
	public int getTargetHeadIdx() { return targetHeadIdx; }
	
	/** j */
	public int getArgHeadIdx() { return argHeadIdx; }
	
	/** k */
	public int getRoleIdx() { return roleIdx; }
	
	public Var getRoleVar() { return headVar; }
	
	public List<Frame> getPossibleFrames() {
		return possibleFrames;
	}
	
	/**
	 * returns the Frame this argument is realized for
	 * (may be nullFrame, indicating that this argument is
	 *  not realized for any frame).
	 */
	public Frame getFrame(int localIdx) {
		return possibleFrames.get(localIdx);
	}
//	public Frame getFrame(VarConfig conf) {
//		int idx = conf.getState(headVar);
//		return possibleFrames.get(idx);
//	}
	
//	public boolean argIsRealized(VarConfig conf) {
//		return getFrame(conf) != Frame.nullFrame;
//	}
	public boolean argIsRealize(int localConfig) {
		return possibleFrames.get(localConfig) != Frame.nullFrame;
	}
	
	public Span getSpanDummy() {
		//System.err.println("FIX ME!!");
		return Span.widthOne(argHeadIdx);
	}
	
//	public Span getSpanDummy(VarConfig conf) {
//		return getSpanDummy();
//	}
}

