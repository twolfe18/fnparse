package edu.jhu.hlt.fnparse.inference.newstuff;

import edu.jhu.gm.model.DenseFactor;
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
 * TODO: subclass something other than DenseFactor which does not require instantiating
 * the full table in memory. We know which entries are 0 vs 1, don't need an array for that.
 * 
 * @author travis
 */
public class ExpansionHardFactor extends DenseFactor {

	private static final long serialVersionUID = 1L;
	
	private Var rowVar;
	private Var expansionVar;	// columns
	
	private int rowNullIdx;
	private int expansionZeroIdx;
	
	
	// TODO support/check for prob domain?
	private static final double ONE = 0d;
	private static final double ZERO = Double.NEGATIVE_INFINITY;
	
	public ExpansionHardFactor(Var rowVar, Var expansionVar, int rowNullIdx, int expansionZeroIdx) {
		super(new VarSet(rowVar, expansionVar), ONE);
		this.rowVar = rowVar;
		this.expansionVar = expansionVar;
		this.rowNullIdx = rowNullIdx;
		this.expansionZeroIdx = expansionZeroIdx;
		
		VarSet vs = this.getVars();
		int n = vs.calcNumConfigs();
		for(int i=0; i<n; i++) {
			VarConfig conf = vs.getVarConfig(i);
			int rowVarIdx = conf.getState(rowVar);
			int expVarIdx = conf.getState(expansionVar);
			if(rowVarIdx == rowNullIdx && expVarIdx != expansionZeroIdx)
				this.setValue(i, ZERO);
		}
	}
	
}

