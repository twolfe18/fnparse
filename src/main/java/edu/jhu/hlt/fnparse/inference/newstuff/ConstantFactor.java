package edu.jhu.hlt.fnparse.inference.newstuff;

import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.IFgModel;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;

public class ConstantFactor implements Factor {

	private static final long serialVersionUID = 1L;
	
	private int id = -1;
	private VarSet vs;
	private double value;
	
	public ConstantFactor(VarSet vs, double value) {
		this.vs = vs;
		this.value = value;
	}
	
	@Override
	public Factor getClamped(VarConfig clmpVarConfig) {
		if(clmpVarConfig.size() == 0) return this;
		else return new ConstantFactor(clmpVarConfig.getVars(), value);
	}

	@Override
	public VarSet getVars() {
		return vs;
	}

	@Override
	public void updateFromModel(FgModel model, boolean logDomain) {}	// no-op

	@Override
	public double getUnormalizedScore(int configId) { return value; }
	
	@Override
	public void addExpectedFeatureCounts(IFgModel counts, double multiplier,
			FgInferencer inferencer, int factorId) {}	// no-op

	@Override
	public int getId() { return id; }

	@Override
	public void setId(int id) { this.id = id; }

}
