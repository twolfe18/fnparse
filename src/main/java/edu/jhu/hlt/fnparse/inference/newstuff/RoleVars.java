package edu.jhu.hlt.fnparse.inference.newstuff;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class RoleVars implements FgRelated {
	
	public static final int maxArgRoleExpandLeft = 999;
	public static final int maxArgRoleExpandRight = 999;
		
	private FrameVar parent;
	private int roleIdx;	// aka "k"
	private int headIdx;	// aka "j", head of the argument span (target head comes from parent)
	private Var headVar;	// binary
	
	private Expansion.Iter expansions;
	private Var expansionVar;
	
	public RoleVars(FrameVar parent, Sentence s, int headIdx, int roleIdx) {
		this.parent = parent;
		this.roleIdx = roleIdx;
		this.headIdx = headIdx;
		String headVarName = String.format("r_{%d,%d,%d}", parent.getTargetHeadIdx(), headIdx, roleIdx);
		this.headVar = new Var(VarType.PREDICTED, 2, headVarName, BinaryVarUtil.stateNames);
		
		this.expansions = new Expansion.Iter(headIdx, s.size(), maxArgRoleExpandLeft, maxArgRoleExpandRight);
		String expVarName = String.format("r^e_{%d,%d,%d}", parent.getTargetHeadIdx(), headIdx, roleIdx);
		this.expansionVar = new Var(VarType.PREDICTED, this.expansions.size(), expVarName, null);
	}
	
	private Boolean headVarGold = null;
	private int expansionVarGold = -1;
	
	public void setGold(FrameInstance fi) {
		
		// compute the gold expansion
		Span argSpan = fi.getArgument(roleIdx);
		Expansion goldExpansion;
		if(argSpan == Span.nullSpan) {
			headVarGold = false;
			goldExpansion = Expansion.noExpansion;
		}
		else {
			headVarGold = true;
			goldExpansion = Expansion.headToSpan(headIdx, argSpan);
		}
				
		// find the index of the gold expansion var
		int goldExpansionIdx = -1;
		for(int i=0; expansions.hasNext(); i++) {
			Expansion ei = expansions.next();
			if(ei.equals(goldExpansion)) {
				goldExpansionIdx = i;
				break;
			}
		}
		if(goldExpansionIdx < 0)
			throw new RuntimeException();
		
		expansionVarGold = goldExpansionIdx;
	}
	
	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		
		// TODO hard factors
		
		if(headVarGold != null)
			gold.put(headVar, BinaryVarUtil.boolToConfig(headVarGold));
		if(expansionVarGold >= 0)
			gold.put(expansionVar, expansionVarGold);
	}
	
	public int getRoleIdx() { return roleIdx; }
	
	public Var getRoleVar() { return headVar; }	// binary var
	public Var getExpansionVar() { return expansionVar; }

}

