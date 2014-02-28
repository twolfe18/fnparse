package edu.jhu.hlt.fnparse.inference.newstuff;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

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
 * @author travis
 */
public class RoleVars implements FgRelated {
	
	public static final int maxArgRoleExpandLeft = 10;
	public static final int maxArgRoleExpandRight = 3;
		
	private FrameVar parent;
	private int roleIdx;	// aka "k"
	private int headIdx;	// aka "j", head of the argument span (target head comes from parent)
	private Var headVar;
	private int headVarGold = -1;
	private Expansion.Iter expansions;
//	private Var expansionVar;
	
	public RoleVars(FrameVar parent, Sentence s, int headIdx, int roleIdx, boolean logDomain) {
		this.parent = parent;
		this.roleIdx = roleIdx;
		this.headIdx = headIdx;
		
		this.expansions = new Expansion.Iter(headIdx, s.size(), maxArgRoleExpandLeft, maxArgRoleExpandRight);
//		String expVarName = String.format("r^e_{%d,%d,%d}", parent.getTargetHeadIdx(), headIdx, roleIdx);
//		this.expansionVar = new Var(VarType.PREDICTED, this.expansions.size(), expVarName, null);
		
		String headVarName = String.format("r_{%d,%d,%d}", parent.getTargetHeadIdx(), headIdx, roleIdx);
//		this.headVar = new Var(VarType.PREDICTED, 2, headVarName, BinaryVarUtil.stateNames);
		
		// new deal: if there are K possible expansions, headVar \in 0 .. K
		// where 0..K-1 are the expansions and K means "null" -- or that this
		// frame-role is not active at this head word j.
		this.headVar = new Var(VarType.PREDICTED, expansions.size()+1, headVarName, null);
	}
	
//	private boolean headVarGold = false;
//	private int expansionVarGold = 0;
	
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
		headVarGold = expansions.size();
//		expansionVarGold = expansions.indexOf(Expansion.noExpansion);
//		if(expansionVarGold < 0) throw new IllegalStateException();
	}
	
	/**
	 * use this to say that this argument was realized in the sentence.
	 */
	public void setGold(Span s) {
		if(s == Span.nullSpan)
			throw new IllegalArgumentException();
		
		// compute the gold expansion
		Expansion goldExpansion = Expansion.headToSpan(headIdx, s);
		headVarGold = expansions.indexOf(goldExpansion);;
//		expansionVarGold = expansions.indexOf(goldExpansion);
//		if(expansionVarGold < 0)
		if(headVarGold < 0 || headVarGold >= expansions.size())
			throw new IllegalStateException("gold expansion for " + s + " @ " + headIdx + " was not found. did you prune too hard?");
	}
	
	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		
//		gold.put(headVar, BinaryVarUtil.boolToConfig(headVarGold));
//		gold.put(expansionVar, expansionVarGold);
		fg.addVar(headVar);
		if(headVarGold < 0) {
			//assert false;
			gold.put(headVar, 0);	// what value?
		}
		else gold.put(headVar, headVarGold);
	}
	
	public int getHeadIdx() { return headIdx; }
	public int getRoleIdx() { return roleIdx; }
	
	public boolean getRoleActive(VarConfig conf) {
//		return BinaryVarUtil.configToBool(conf.getState(headVar));
		int config = conf.getState(headVar);
		return config < expansions.size();
	}
//	public Expansion getExpansion(VarConfig conf) {
//		return expansions.get(conf.getState(expansionVar));
//	}
	
	public Span getSpan(VarConfig conf) {
		int config = conf.getState(headVar);
		if(config == expansions.size())
			return Span.nullSpan;
		Expansion e = expansions.get(config);
		return e.upon(headIdx);
	}
	
	public Var getRoleVar() { return headVar; }
//	public Var getExpansionVar() { return expansionVar; }

	public int getNumExpansions() { return expansions.size(); }
}

