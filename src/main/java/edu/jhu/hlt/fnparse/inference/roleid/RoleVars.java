package edu.jhu.hlt.fnparse.inference.roleid;

import java.util.Iterator;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.misc.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.misc.FgRelated;
import edu.jhu.hlt.fnparse.inference.misc.Parser;
import edu.jhu.hlt.fnparse.inference.misc.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.pruning.ArgPruner;

/**
 * Represents all of the role information for a given frame instantiation (target).
 * 
 * @author travis
 */
public class RoleVars implements FgRelated {

	public static final int maxArgRoleExpandLeft = 10;
	public static final int maxArgRoleExpandRight = 3;
	
	
	// TODO fill out expansion vars
	

	// frame-target that this belongs to
	public final int i;
	public final Frame t;

	public final int n;	// length of sentence

	// the indices of r_jk and r_jk_e correspond
	// r_jk[k][N], where N=sentence.size, represents this arg not being realized
	// there will be an Exactly1 factor on each r_jk[j] forall j
	public Var[][] r_kj;	// [k][j], may contain null values
	public Var[][] r_kj_e;	// [k][j], may contain null values
	
	public boolean[][] r_kj_gold;
	public int[][] r_kj_e_gold;

	public VarType varType;	// if f_it != gold, then latent, otherwise predicted

	public RoleVars(int targetHeadIdx, Frame evoked, VarType varType, Sentence s, ArgPruner argPruner) {

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
	
	public Frame getFrame() { return t; }
	
	public int getTargetHead() { return i; }
	
	public void setGold(FrameInstance fi, ParserParams params) {
		assert fi.getFrame() == t;
		assert fi.getTarget().start == i && fi.getTarget().width() == 1 : "sanity check";
		
		final int K = t.numRoles();
		r_kj_gold = new boolean[K][n+1];
		
		for(int k=0; k<K; k++) {
			Span s = fi.getArgument(k);
			int jGold = s == Span.nullSpan ? n : params.headFinder.head(s, fi.getSentence());
			for(int j=0; j<=n; j++)
				r_kj_gold[k][j] = (j == jGold);
		}
	}
	
	/** declares that this frame, and hence its arguments, is not realized in the sentence */
	public void setGoldIsNull() {
		final int K = t.numRoles();
		r_kj_gold = new boolean[K][n+1];
		for(int k=0; k<K; k++)
			r_kj_gold[k][n] = true;
	}
	
	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		final int K = t.numRoles();
		for(int k=0; k<K; k++)
			for(int j=0; j<=n; j++)
				gold.put(r_kj[k][j], BinaryVarUtil.boolToConfig(r_kj_gold[k][j]));
		// no real need to add the variables, the factors should do that for us
	}
		
	
	public static class RVar {
		public int j, k;
		public Var var;
		public RVar(Var v, int k, int j) {
			this.var = v;
			this.j = j;
			this.k = k;
		}
	}
	public static class RVarIter implements Iterator<RVar> {
		private Var[][] vars;
		public int j, k;
		public RVarIter(Var[][] vars) {
			this.vars = vars;
			this.k = 0;
			this.j = 0;
		}
		@Override
		public boolean hasNext() {
			return k < vars.length || j < vars[k].length;
		}
		@Override
		public RVar next() {
			RVar r = new RVar(vars[k][j], k, j);
			j++;
			if(j == vars[k].length) {
				k++;
				j = 0;
			}
			return r;
		}
		@Override
		public void remove() { throw new UnsupportedOperationException(); }
	}
	public Iterator<RVar> getVars() {		// TODO name something more appropriate when we incorporate expansion vars
		return new RVarIter(this.r_kj);
	}
}

