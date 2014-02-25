package edu.jhu.hlt.fnparse.inference.newstuff;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class RoleVars implements FgRelated {
	
	public static final int maxArgRoleExpandLeft = 10;
	public static final int maxArgRoleExpandRight = 3;
		
	private FrameVar parent;
	private int roleIdx;	// aka "k"
	private int headIdx;	// aka "j", head of the argument span (target head comes from parent)
	private Var headVar;	// binary
	
	private Expansion.Iter expansions;
	private Var expansionVar;
	
	public RoleVars(FrameVar parent, Sentence s, int headIdx, int roleIdx, boolean logDomain) {
		this.parent = parent;
		this.roleIdx = roleIdx;
		this.headIdx = headIdx;
		String headVarName = String.format("r_{%d,%d,%d}", parent.getTargetHeadIdx(), headIdx, roleIdx);
		this.headVar = new Var(VarType.PREDICTED, 2, headVarName, BinaryVarUtil.stateNames);
		
		this.expansions = new Expansion.Iter(headIdx, s.size(), maxArgRoleExpandLeft, maxArgRoleExpandRight);
		String expVarName = String.format("r^e_{%d,%d,%d}", parent.getTargetHeadIdx(), headIdx, roleIdx);
		this.expansionVar = new Var(VarType.PREDICTED, this.expansions.size(), expVarName, null);
		
	}
	
	private boolean headVarGold = false;
	private int expansionVarGold = 0;
	
	public static class Location {
		public int frame, arg, role;
		public int hashCode() { return (frame << 20) | (arg << 10) | role; }
		public boolean equals(Object other) {
			if(other instanceof Location) {
				Location l = (Location) other;
				return frame == l.frame && arg == l.arg && role == l.arg;
			}
			else return false;
		}
	}
	
	public Location getLocation() {
		Location l = new Location();
		l.frame = parent.getTargetHeadIdx();
		l.arg = headIdx;
		l.role = roleIdx;
		return l;
	}
	
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
			throw new IllegalStateException("gold expansion for " + s + " @ " + headIdx + " was not found. did you prune too hard?");
	}
	
	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		gold.put(headVar, BinaryVarUtil.boolToConfig(headVarGold));
		gold.put(expansionVar, expansionVarGold);
	}
	
	public int getHeadIdx() { return headIdx; }
	public int getRoleIdx() { return roleIdx; }
	
	public boolean getRoleActive(VarConfig conf) {
		return BinaryVarUtil.configToBool(conf.getState(headVar));
	}
	public Expansion getExpansion(VarConfig conf) {
		return expansions.get(conf.getState(expansionVar));
	}
	public Span getSpan(VarConfig conf) {
		Expansion e = getExpansion(conf);
		return e.upon(headIdx);
	}
	
	public Var getRoleVar() { return headVar; }	// binary var
	public Var getExpansionVar() { return expansionVar; }

	public int getNumExpansions() { return expansions.size(); }
}

