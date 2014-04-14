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
import edu.jhu.hlt.fnparse.inference.misc.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.misc.FgRelated;
import edu.jhu.hlt.fnparse.inference.misc.Parser.ParserParams;

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

	public static final int maxArgRoleExpandLeft = 10;
	public static final int maxArgRoleExpandRight = 3;
	
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

	private RoleVars(FrameInstance gold, boolean hasGold, int targetHeadIdx, Frame evoked, Sentence sent, ParserParams params) {

		if(evoked == Frame.nullFrame)
			throw new IllegalArgumentException("only create these for non-nullFrame f_it");
		
		if(hasGold && gold.getFrame() != evoked)
			throw new IllegalArgumentException();

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
				jGoldSpan = gold.getArgument(k);
				jGold = jGoldSpan == Span.nullSpan ? n : params.headFinder.head(jGoldSpan, gold.getSentence());
			}

			for(int j=0; j<n; j++) {

				if(params.argPruner.pruneArgHead(t, k, j, sent))
					continue;

				boolean argRealized = (j == jGold);

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
						goldConf.put(r_kj_e[k][j], eGoldI);
						assert eGoldI >= 0;
						assert eGoldI < r_kj_e_values[k][j].size();
					}
				}

				inThisRow++;
			}
			
			// there is no expansion variable for null-realized-arg
			r_kj[k][n] = new Var(VarType.PREDICTED, 2, String.format("r_{i=%d,t=%s,k=%d,notRealized}", i, evoked.getName(), k), BinaryVarUtil.stateNames);
			if(hasGold)
				goldConf.put(r_kj[k][n], BinaryVarUtil.boolToConfig(n == jGold));
			
			// TODO
			assert inThisRow >= 2 : "fixme";
		}
	}

	/** constructor for prediction */
	public RoleVars(int targetHeadIdx, Frame evoked, Sentence s, ParserParams params) {
		this(null, false, targetHeadIdx, evoked, s, params);
	}

	/**
	 * constructor for training
	 * @param gold may be null if these roles should be predicted as "not-realized". This may happen
	 *        for example if the frame predicted is incorrect (can save precision by not making predictions in this case).
	 */
	public RoleVars(FrameInstance gold, int targetHeadIdx, Frame evoked, Sentence s, ParserParams params) {
		this(gold, true, targetHeadIdx, evoked, s, params);
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
			while(hasNext() && roleVars[k][j] == null)
				bump();
		}
		private void bump() {
			j++;
			if(j == roleVars[k].length) {
				k++;
				j = 0;
			}
		}
		@Override
		public boolean hasNext() {
			return k < roleVars.length && j < roleVars[k].length;
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

	public Iterator<RVar> getVars() {
		return new RVarIter(this.r_kj, this.r_kj_e, this.r_kj_e_values);
	}

	public Frame getFrame() { return t; }
	
	public int getTargetHead() { return i; }

}

