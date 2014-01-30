package edu.jhu.hlt.fnparse.inference.factors;

import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.ExplicitFactor;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.gm.util.IntIter;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.inference.variables.FrameHypothesis;
import edu.jhu.hlt.fnparse.inference.variables.RoleHypothesis;

/**
 * enforces that r_ijk = nullSpan \forall k \ge f_i.numRoles
 * this is a binary factor between a frame variable and a role variable.
 * @author travis
 */
public class NumRoleHardFactor {
	
	private ExplicitFactor f;
	
	public NumRoleHardFactor(FrameHypothesis f, RoleHypothesis r, boolean useLogValues) {
		double validValue = useLogValues ? 0d : 1d;
		double invalidValue = useLogValues ? Double.NEGATIVE_INFINITY : 0d;
		VarSet vs = new VarSet(f.getVar(), r.getVar());
		DenseFactor df = new DenseFactor(vs);
		IntIter iiter = vs.getConfigIter(vs);
		while(iiter.hasNext()) {
			int cfgIdx = iiter.next();
			VarConfig cfg = vs.getVarConfig(cfgIdx);
			int frameIdx = cfg.getState(f.getVar());
			Frame frame = f.getPossibleFrame(frameIdx);
			df.setValue(cfgIdx, r.getRoleIdx() >= frame.numRoles() ? invalidValue : validValue);
		}
		this.f = new ExplicitFactor(df);
	}
	
	public ExplicitFactor getFactor() { return f; }
}
