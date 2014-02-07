package edu.jhu.hlt.fnparse.inference.newstuff;

import edu.jhu.gm.model.ExplicitFactor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class RoleVars implements FgRelated {
	
	public static final int maxArgRoleExpandLeft = 999;
	public static final int maxArgRoleExpandRight = 999;
		
	private FrameVar parent;	// TODO this can be removed (good for debugging)
	private int roleIdx;	// aka "k"
	private int headIdx;	// aka "j", head of the argument span (target head comes from parent)
	private Var headVar;	// binary
	
	private Expansion.Iter expansions;
	private Var expansionVar;
	
	private ExpansionHardFactor expansionHardFactor;
	
	public RoleVars(FrameVar parent, Sentence s, int headIdx, int roleIdx, boolean logDomain) {
		this.parent = parent;
		this.roleIdx = roleIdx;
		this.headIdx = headIdx;
		String headVarName = String.format("r_{%d,%d,%d}", parent.getTargetHeadIdx(), headIdx, roleIdx);
		this.headVar = new Var(VarType.PREDICTED, 2, headVarName, BinaryVarUtil.stateNames);
		
		this.expansions = new Expansion.Iter(headIdx, s.size(), maxArgRoleExpandLeft, maxArgRoleExpandRight);
		String expVarName = String.format("r^e_{%d,%d,%d}", parent.getTargetHeadIdx(), headIdx, roleIdx);
		this.expansionVar = new Var(VarType.PREDICTED, this.expansions.size(), expVarName, null);
		
		this.expansionHardFactor = new ExpansionHardFactor(headVar, expansionVar,
				BinaryVarUtil.boolToConfig(false), this.expansions.indexOf(Expansion.noExpansion), logDomain);
	}
	
	private Boolean headVarGold = null;
	private int expansionVarGold = -1;
	
	/**
	 * use this to say that this argument was not instantiated.
	 */
	public void setGoldIsNull() {
		headVarGold = false;
		expansionVarGold = expansions.indexOf(Expansion.noExpansion);
		if(expansionVarGold < 0) throw new IllegalStateException();
	}
	
	/**
	 * use this to say that this argument was realized in the sentence.
	 */
	public void setGold(Span s) {

		if(s == Span.nullSpan)
			throw new IllegalArgumentException();
		
		headVarGold = true;
		
		// compute the gold expansion
		Expansion goldExpansion = Expansion.headToSpan(headIdx, s);
		expansionVarGold = expansions.indexOf(goldExpansion);
		if(expansionVarGold < 0)
			throw new IllegalArgumentException();
	}
	
	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		
		fg.addFactor(new ExplicitFactor(expansionHardFactor));
		
		if(headVarGold != null)
			gold.put(headVar, BinaryVarUtil.boolToConfig(headVarGold));
		if(expansionVarGold >= 0)
			gold.put(expansionVar, expansionVarGold);
	}
	
	public int getHeadIdx() { return headIdx; }
	public int getRoleIdx() { return roleIdx; }
	
	public Var getRoleVar() { return headVar; }	// binary var
	public Var getExpansionVar() { return expansionVar; }

}

