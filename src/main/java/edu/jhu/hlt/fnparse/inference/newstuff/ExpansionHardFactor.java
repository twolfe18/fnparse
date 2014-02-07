package edu.jhu.hlt.fnparse.inference.newstuff;

import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.IFgModel;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;

/**
 * This frame enforces constraints of the form: x.head = null  =>  x.expansion = 0
 * As a table:
 *                   expansion = 0  expansion = (1,0)  expansion = (0,1)  ...  expansion = (n,m)
 * head = nullFrame      ok            BAD                BAD                      BAD
 * head = f1             ok            ok                 ok              ...      ok
 * head = f2             ok            ok                 ok              ...      ok
 * ...                   ...           ...                ...                      ...
 * head = fn             ok            ok                 ok              ...      ok
 * 
 * Note that this should not be used in all cases, only where needed. The Frame-Role factor
 * combines a hard and soft factor for efficiency, and it doesn't need to compute features
 * for cells that the hard factor would rule out. This factor takes the naive approach and
 * just multiplies in a 0 in places where you could possibly avoid doing computation.
 * 
 * @author travis
 */
public class ExpansionHardFactor implements Factor {

	private static final long serialVersionUID = 1L;

	private int id = -1;

	private Var rowVar;
	private Var expansionVar;	// columns
	private VarSet vars;

	private int rowNullIdx;
	private int expansionZeroIdx;
	
	private boolean logDomain;
	
	public ExpansionHardFactor(Var rowVar, Var expansionVar, int rowNullIdx, int expansionZeroIdx, boolean logDomain) {
		this.rowVar = rowVar;
		this.expansionVar = expansionVar;
		this.rowNullIdx = rowNullIdx;
		this.expansionZeroIdx = expansionZeroIdx;
		this.vars = new VarSet(rowVar, expansionVar);
		this.logDomain = logDomain;
	}

	@Override
	public Factor getClamped(VarConfig clmpVarConfig) {
		if(clmpVarConfig.size() == 0) return this;
		switch(clmpVarConfig.size()) {
		case 0: return this;
		case 2:
			double v = getUnormalizedScore(clmpVarConfig.getConfigIndex());
			return new ConstantFactor(new VarSet(), v);
		case 1:
		default:
			throw new RuntimeException("i don't know how to do this");
		}
	}

	public double one() { return logDomain ? 0d : 1d; }
	public double zero() { return logDomain ? Double.NEGATIVE_INFINITY : 0d; }


	@Override
	public VarSet getVars() { return vars; }


	@Override
	public void updateFromModel(FgModel model, boolean logDomain) {
		// no-op
		if(logDomain != this.logDomain)
			throw new IllegalStateException("this.logDomain=" + this.logDomain);
	}

	@Override
	public double getUnormalizedScore(int configId) {
		VarConfig conf = vars.getVarConfig(configId);
		int rowVarIdx = conf.getState(rowVar);
		int expVarIdx = conf.getState(expansionVar);
		if(rowVarIdx == rowNullIdx && expVarIdx != expansionZeroIdx)
			return zero();
		else return one();
	}

	@Override
	public void addExpectedFeatureCounts(IFgModel counts, double multiplier, FgInferencer inferencer, int factorId) {}	// no-op

	@Override
	public int getId() { return id; }

	@Override
	public void setId(int id) { this.id = id; }

}

