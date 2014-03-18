package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.*;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.inference.pruning.ArgPruner;

/**
 * @author travis
 */
public class RoleVars implements FgRelated {
	
	
	
	public static class NewRoleVars {
		
		// frame-target that this belongs to
		public int i;
		public Frame t;
		
		public int n;	// length of sentence
		
		// the indices of r_jk and r_jk_e correspond
		// r_jk[k][N], where N=sentence.size, represents this arg not being realized
		// there will be an Exactly1 factor on each r_jk[j] forall j
		public Var[][] r_kj;	// [k][j], may contain null values
		public Var[][] r_kj_e;	// [k][j], may contain null values
		
		public VarType varType;	// if f_it != gold, then latent, otherwise predicted
		
		public NewRoleVars(int targetHeadIdx, Frame evoked, VarType varType, Sentence s, ArgPruner argPruner) {
			
			if(evoked == Frame.nullFrame)
				throw new IllegalArgumentException("only create these for non-nullFrame f_it");
			
			this.i = targetHeadIdx;
			this.t = evoked;
			this.n = s.size();
			this.varType = varType;
			
			int K = evoked.numRoles();
			r_kj = new Var[K][n+1];
			//r_jk_e = new Var[K][n+1];
			for(int k=0; k<K; k++) {
				for(int j=0; j<n; j++) {
					if(argPruner.pruneArgHead(t, k, j, s))
						continue;
					String name = String.format("r_{i=%d,j=%d,k=%d}", i, j, k);
					r_kj[k][j] = new Var(varType, 2, name, BinaryVarUtil.stateNames);
				}
			}
		}
	}
	
	
	
	
	
	public static final int maxArgRoleExpandLeft = 10;
	public static final int maxArgRoleExpandRight = 3;
		
	// meta information
	private int targetHeadIdx;	// aka "i"
	private int argHeadIdx;		// aka "j", head of the argument span (target head comes from parent)
	private int roleIdx;		// aka "k"
	
	// if(training) 
	//   latent = (f_i == Frame.nullFrame)
	// else if(doingFrameDecode)
	//   latent = true
	// else
	//   prune = (f_i == Frame.nullFrame)	// not latent or predicted!
	private VarType varType;
	
	// primary var
	private List<Frame> possibleFrames;
	private Var headVar;			// same domain as f_i, but the index for nullFrame means "off" -- or arg not realized
	private int headVarGold = -1;

	// expansion related
	private Expansion.Iter expansions;
	private Var expansionVar;
	private int expansionVarGold = -1;
	
	
	
	// NOTE: i think that we need a special vale for "arg not realized"/"nullSpan"/"nullExpansion"
	// i think right now we're biased towards whatever we set as the gold label for non-realized arguments.
	
	// lets test this by setting the default to something else and see if we see that bias pop up.
	
	
	
	/**
	 * might return null if the only possible frame is nullFrame
	 */
	public static RoleVars tryToSetup(VarType latentOrPredicted, List<Frame> possibleFrames, Sentence s,
			int targetHeadIdx, int argHeadIdx, int roleIdx, boolean logDomain) {

		// we need to prune down the number of frames based on roleIdx
		List<Frame> feasibleFrames = new ArrayList<Frame>();
		feasibleFrames.add(Frame.nullFrame);	// represents "arg is not realized"
		for(Frame f : possibleFrames)
			if(roleIdx < f.numRoles() && f != Frame.nullFrame)
				feasibleFrames.add(f);

		if(feasibleFrames.size() < 2)
			return null;
		else {
			return new RoleVars(latentOrPredicted, feasibleFrames, s,
					targetHeadIdx, argHeadIdx, roleIdx, logDomain);
		}
	}
	
	private RoleVars(VarType latentOrPredicted, List<Frame> possibleFrames, Sentence s,
			int targetHeadIdx, int argHeadIdx, int roleIdx, boolean logDomain) {
		
		this.possibleFrames = possibleFrames;
		this.targetHeadIdx = targetHeadIdx;
		this.argHeadIdx = argHeadIdx;
		this.roleIdx = roleIdx;
		this.varType = latentOrPredicted;
		
		String headVarName = String.format("r_{%d,%d,%d}", targetHeadIdx, argHeadIdx, roleIdx);
		this.headVar = new Var(latentOrPredicted, this.possibleFrames.size(), headVarName, null);
		
		int maxLeft = Math.min(maxArgRoleExpandLeft, argHeadIdx);
		if(targetHeadIdx < argHeadIdx)
			maxLeft = Math.min(maxLeft, argHeadIdx - targetHeadIdx);
		
		int maxRight = Math.min(maxArgRoleExpandRight, s.size() - argHeadIdx - 1);
		if(targetHeadIdx > argHeadIdx)
			maxRight = Math.min(maxRight, targetHeadIdx - argHeadIdx);
		
		this.expansions = new Expansion.Iter(argHeadIdx, s.size(), maxLeft, maxRight);
		String expVarName = String.format("r^e_{%d,%d,%d}", targetHeadIdx, argHeadIdx, roleIdx);
		this.expansionVar = new Var(latentOrPredicted, this.expansions.size(), expVarName, null);
	}
	
	@Override
	public String toString() {
		return String.format("<r_{i=%d, j=%d, k=%d} domain={%d frames} varType=%s>",
				targetHeadIdx, argHeadIdx, roleIdx, possibleFrames.size(), varType);
	}
	
	/**
	 * use this to say that this argument was not instantiated.
	 */
	public void setGoldIsNull() {
		headVarGold = possibleFrames.indexOf(Frame.nullFrame);
		if(headVarGold < 0) throw new IllegalStateException();
		
		expansionVarGold = 0;	// arbitrary
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
		
		Expansion goldE = Expansion.headToSpan(argHeadIdx, s);
		expansionVarGold = expansions.indexOf(goldE);
		if(expansionVarGold < 0) {
			System.err.println("[RoleVars setGold] couldn't set gold expansion to " + s + " because it was pruned");
			expansionVarGold = 0;
		}
	}
	
	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		fg.addVar(headVar);
		fg.addVar(expansionVar);
		if(varType == VarType.PREDICTED) {
			gold.put(headVar, headVarGold);
			gold.put(expansionVar, expansionVarGold);
		}
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

	public int numExpansions() { return expansions.size(); }
	
	public Var getExpansionVar() { return expansionVar; }
	
	/**
	 * returns the Frame this argument is realized for
	 * (may be nullFrame, indicating that this argument is
	 *  not realized for any frame).
	 */
	public Frame getFrame(int localIdx) {
		return possibleFrames.get(localIdx);
	}

	public Frame getFrame(VarConfig conf) {
		int idx = conf.getState(headVar);
		return possibleFrames.get(idx);
	}
	
//	public boolean argIsRealized(VarConfig conf) {
//		return getFrame(conf) != Frame.nullFrame;
//	}
	public boolean argIsRealize(int localConfig) {
		return possibleFrames.get(localConfig) != Frame.nullFrame;
	}
	
//	public Span getSpanDummy() {
//		//System.err.println("FIX ME!!");
//		return Span.widthOne(argHeadIdx);
//	}
	
	public Span getSpan(int localIdx) {
		return expansions.get(localIdx).upon(argHeadIdx);
	}
	
	public Span getSpan(VarConfig conf) {
		int cfg = conf.getState(expansionVar);
		return expansions.get(cfg).upon(argHeadIdx);
	}
	
}

