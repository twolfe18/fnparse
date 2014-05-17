package edu.jhu.hlt.fnparse.inference.spans;

import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * 
 * @author travis
 *
 */
public class ExpansionVar {

	private static final int UNLABELED = -1;
	private static final int PRUNED_EXPANSION = -2;
	private static final int FAlSE_POS_ARG = -3;

	public final int i;
	public final int fiIdx;
	public final int j;
	public final int k;
	public final FNParse onlyHeads;
	public Var var;
	public Expansion.Iter values;
	private int goldIdx;
	
	public ExpansionVar(int i, int fiIdx, int j, int k, FNParse onlyHeads, Expansion.Iter values, Span goldSpan) {
		this.i = i;
		this.fiIdx = fiIdx;
		this.j = j;
		this.k = k;
		this.onlyHeads = onlyHeads;
		this.values = values;
		if(goldSpan == null)
			this.goldIdx = UNLABELED;
		else if(goldSpan == Span.nullSpan)
			this.goldIdx = FAlSE_POS_ARG;
		else {
			Expansion e = Expansion.headToSpan(j, goldSpan);
			int ei = values.indexOf(e);
			if(ei < 0)
				this.goldIdx = PRUNED_EXPANSION;
			else
				this.goldIdx = ei;
		}
		String name = String.format("r_{i=%d,t=%s,j=%d,k=%d}^e", i, getFrame().getName(), j, k);
		this.var = new Var(VarType.PREDICTED, values.size(), name, null);
	}
	
	public int getTargetHeadIdx() { return i; }
	public Frame getFrame() { return onlyHeads.getFrameInstance(fiIdx).getFrame(); }
	public int getArgHeadIdx() { return j; }
	public int getRole() { return k; }
	
	public boolean hasGold() {
		return goldIdx != UNLABELED;
	}
	
	public void addToGoldConfig(VarConfig goldConf) {
		assert hasGold();
		int i = this.goldIdx;
		// TODO
		// if i == PRUNED_EXPANSION, don't train on this example
		// if i == FALSE_POS_ARG, don't train on this example
		if(i < 0)
			i = values.indexOf(Expansion.headToSpan(j, Span.widthOne(j)));
		goldConf.put(this.var, i);
	}
	
	public Span getSpan(int configIdx) {
		Expansion e = values.get(configIdx);
		return e.upon(j);
	}

	public Span getGoldSpan() {
		assert goldIdx >= 0;
		Expansion e = values.get(goldIdx);
		return e.upon(j);
	}
	
	public Span decodeSpan(FgInferencer hasMargins) {
		DenseFactor df = hasMargins.getMarginals(var);
		return getSpan(df.getArgmaxConfigId());
	}
}