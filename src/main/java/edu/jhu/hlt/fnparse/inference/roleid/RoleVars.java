package edu.jhu.hlt.fnparse.inference.roleid;

import java.util.Iterator;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FgRelated;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;

/**
 * Represents all of the role information for a given frame instantiation (target).
 * 
 * The only trick here is dealing with expansion variables. At train time, r_itjk^e
 * corresponding to r_itjk == nullFrame will be LATENT. At prediction time, all
 * r_itjk^e are PREDICTED.
 * 
 * @author travis
 */
public class RoleVars implements FgRelated {

	// if you check the pareto frontier in ExpansionPruningExperiment:
	// (6,0) gives 77.9 % recall
	// (6,3) gives 86.7 % recall
	// (8,3) gives 88.4 % recall
	// (8,4) gives 90.0 % recall
	// (10,5) gives 92.3 % recall
	// (12,5) gives 93.2 % recall
	public static final int maxArgRoleExpandLeft = 8;
	public static final int maxArgRoleExpandRight = 3;
	
	private static int prunedExpansions = 0, totalExpansions = 0;
	
	public boolean verbose = true;
	
	// frame-target that this belongs to
	public final int i;
	public final Frame t;

	public final int n;	// length of sentence

	// the indices of r_jk and r_jk_e correspond
	// r_jk[k][N], where N=sentence.size, represents this arg not being realized
	// there will be an Exactly1 factor on each r_jk[j] forall j
	public Var[][] r_kj;	// [k][j], may contain null values
	public Var[][] r_kj_e;	// [k][j], may contain null values
	public Expansion.Iter[][] r_kj_e_values;
	
	public VarConfig goldConf;
	public FrameInstance gold;
	public ParserParams params;
	
	public boolean hasLabels() { return goldConf != null; }

	private RoleVars(FrameInstance gold, boolean gotFramePredictionWrong, boolean hasGold, int targetHeadIdx, Frame evoked, Sentence sent, ParserParams params) {

		if(evoked == Frame.nullFrame)
			throw new IllegalArgumentException("only create these for non-nullFrame f_it");
		
		this.i = targetHeadIdx;
		this.t = evoked;
		this.n = sent.size();
		this.gold = gold;
		if(hasGold)
			this.goldConf = new VarConfig();

		int K = evoked.numRoles();
		r_kj = new Var[K][n+1];
		r_kj_e = new Var[K][n];
		r_kj_e_values = new Expansion.Iter[K][n];
		for(int k=0; k<K; k++) {
			int inThisRow = 1;

			Span jGoldSpan = null;
			int jGold = -1;
			if(hasGold) {
				jGoldSpan = gotFramePredictionWrong ? Span.nullSpan : gold.getArgument(k);
				jGold = jGoldSpan == Span.nullSpan ? n : params.headFinder.head(jGoldSpan, gold.getSentence());
			}

			for(int j=0; j<n; j++) {

				boolean argRealized = (j == jGold);

				if(params.argPruner.pruneArgHead(t, k, j, sent)) {
					if(argRealized) {
						params.argPruner.falsePrune();
						if(verbose) {
							System.err.printf("[RoleVars] pruned %s.%s for head \"%s\"\n",
									gold.getFrame().getName(), gold.getFrame().getRole(k), sent.getWord(j));
						}
					}
					continue;
				}

				String name = String.format("r_{i=%d,t=%s,j=%d,k=%d}", i, evoked.getName(), j, k);
				r_kj[k][j] = new Var(VarType.PREDICTED, 2, name, BinaryVarUtil.stateNames);

				VarType expansionType = VarType.PREDICTED;
				if(hasGold && !argRealized)
					expansionType = VarType.LATENT;
				setExpansionVarFor(i, evoked, j, k, sent, expansionType);

				if(hasGold) {
					goldConf.put(r_kj[k][j], BinaryVarUtil.boolToConfig(argRealized));
					if(argRealized) {	// expansion variables for non-instantiated arguments should be latent
						Expansion eGold = Expansion.headToSpan(j, jGoldSpan);
						int eGoldI = r_kj_e_values[k][j].indexOf(eGold);
						if(eGoldI < 0) {
							System.err.printf("[RoleVars] pruned gold expansion for %s.%s @ %d: %s\n",
									t.getName(), t.getRole(k), j, eGold);
							System.err.printf("[RoleVars] of %d roles, we pruned %d away (%.1f%%)\n",
									totalExpansions+1, prunedExpansions+1, (100d*(prunedExpansions+1))/(totalExpansions+1));
							eGold = Expansion.headToSpan(j, Span.widthOne(j));
							eGoldI = r_kj_e_values[k][j].indexOf(eGold);
							assert eGoldI >= 0;
							prunedExpansions++;
						}
						totalExpansions++;
						goldConf.put(r_kj_e[k][j], eGoldI);
						assert eGoldI >= 0;
						assert eGoldI < r_kj_e_values[k][j].size();
					}
				}

				inThisRow++;
			}
			
			if(inThisRow == 0) {	// if all roles were pruned, then no need to use that var (or the "no arg" var)
				r_kj[k] = null;
				r_kj_e[k] = null;
				r_kj_e_values[k] = null;
				// i'm not removing the Vars from goldConf because it doesn't have a drop method, probably doesn't matter
			}
			else {
				// there is no expansion variable for null-realized-arg
				r_kj[k][n] = new Var(VarType.PREDICTED, 2, String.format("r_{i=%d,t=%s,k=%d,notRealized}", i, evoked.getName(), k), BinaryVarUtil.stateNames);
				if(hasGold) {
					boolean goldIsNull = gotFramePredictionWrong || (n == jGold);
					goldConf.put(r_kj[k][n], BinaryVarUtil.boolToConfig(goldIsNull));
				}
			}
		}
	}

	/** constructor for prediction */
	public RoleVars(int targetHeadIdx, Frame evoked, Sentence s, ParserParams params) {
		this(null, false, false, targetHeadIdx, evoked, s, params);
	}

	/**
	 * constructor for training.
	 * 
	 * This will handle the case where gold's Frame is different from evoked (t).
	 * If they are the same, then the normal thing happens: all of the r_itjk are set
	 *   according to the arguments that actually appeared.
	 * If they are different, then r_itjk are set to have a gold value of "not realized"
	 */
	public RoleVars(FrameInstance gold, int targetHeadIdx, Frame evoked, Sentence s, ParserParams params) {
		this(gold, gold == null || gold.getFrame() != evoked, true, targetHeadIdx, evoked, s, params);
	}
	
	private void setExpansionVarFor(int i, Frame t, int j, int k, Sentence s, VarType varType) {
		String name = String.format("r_{i=%d,t=%s,j=%d,k=%d}^e", i, t.getName(), j, k);
		
		// make sure expanding right/left wouldn't overlap the target
		int maxLeft = maxArgRoleExpandLeft;
		int maxRight = maxArgRoleExpandRight;
		if(j > i && j - maxArgRoleExpandLeft >= i)
			maxLeft = j - i;
		if(j < i && j + maxArgRoleExpandRight > i)
			maxRight = i - j;
		
		Expansion.Iter ei = new Expansion.Iter(j, s.size(), maxLeft, maxRight);
		r_kj_e[k][j] = new Var(varType, ei.size(), name, null);
		r_kj_e_values[k][j] = ei;
	}
	
	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		// i dont think i really need to add the variables to fg
		if(hasLabels())
			gold.put(this.goldConf);
	}
	
	public Var getExpansionVar(int j, int k) {
		if(j < 0 || j >= n)
			throw new IllegalArgumentException();
		if(k < 0 || k >= t.numRoles())
			throw new IllegalArgumentException();
		Var e = r_kj_e[k][j];
		if(e == null)
			throw new RuntimeException();
		return e;
	}
	
	public Span getArgSpanFor(int expansionVarConfigId, int j, int k) {
		if(j < 0 || j >= n)
			throw new IllegalArgumentException();
		if(k < 0 || k >= t.numRoles())
			throw new IllegalArgumentException();
		Expansion.Iter ei = r_kj_e_values[k][j];
		if(expansionVarConfigId < 0 || expansionVarConfigId >= ei.size())
			throw new RuntimeException();
		return ei.get(expansionVarConfigId).upon(j);
	}
	
	public static final class RVar {
		public int j, k;
		public Var roleVar;			// binary
		public Var expansionVar;	// k-ary, expansion-valued
		public Expansion.Iter expansionValues;
		public RVar(Var roleVar, Var expansionVar, Expansion.Iter expansionValues, int k, int j) {
			this.roleVar = roleVar;
			this.expansionVar = expansionVar;
			this.expansionValues = expansionValues;
			this.j = j;
			this.k = k;
		}
		@Override
		public String toString() {
			return String.format("<RVar j=%d k=%d %s>", j, k, roleVar.toString());
		}
	}
	public static class RVarIter implements Iterator<RVar> {
		private Var[][] roleVars;
		private Var[][] expansionVars;
		private Expansion.Iter[][] expansionValues;
		public int j, k;
		public RVarIter(Var[][] roleVars, Var[][] expansionVars, Expansion.Iter[][] expansionValues) {
			this.roleVars = roleVars;
			this.expansionVars = expansionVars;
			this.expansionValues = expansionValues;
			this.k = 0;
			this.j = 0;
			while(hasNext() && (roleVars[k] == null || roleVars[k][j] == null))
				bump();
		}
		private void bump() {
			j++;
			if(j == roleVars[k].length) {
				do { k++; }
				while(k < roleVars.length && roleVars[k] == null);
				j = 0;
			}
		}
		@Override
		public boolean hasNext() {
			return k < roleVars.length && roleVars[k] != null && j < roleVars[k].length;
		}
		@Override
		public RVar next() {
			Var e = null;
			Expansion.Iter ei = null;
			if(j < expansionVars[k].length) {
				e = expansionVars[k][j];
				ei = expansionValues[k][j];
			}
			RVar r = new RVar(roleVars[k][j], e, ei, k, j);
			do { bump(); }
			while(hasNext() && roleVars[k][j] == null);
			return r;
		}
		@Override
		public void remove() { throw new UnsupportedOperationException(); }
	}

	/**
	 * returns an iterator of unpruned (a wrapper around) role variables (RVars).
	 * an RVar will always have a roleVar, but its expansion may be null (in the case
	 * of the roleVar for "arg not realized").
	 */
	public Iterator<RVar> getVars() {
		return new RVarIter(this.r_kj, this.r_kj_e, this.r_kj_e_values);
	}

	public Frame getFrame() { return t; }
	
	public int getTargetHead() { return i; }

}

