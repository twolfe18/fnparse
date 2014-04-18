package edu.jhu.hlt.fnparse.inference.jointid;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.features.Refinements;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars.RVar;

public class JointFactorFactory implements FactorFactory<FrameInstanceHypothesis> {

	private static final long serialVersionUID = 1L;
	
	private ParserParams params;
	
	public JointFactorFactory(ParserParams params) {
		this.params = params;
	}

	@Override
	public List<Factor> initFactorsFor(Sentence s, List<FrameInstanceHypothesis> inThisSentence, ProjDepTreeFactor l) {

		// only need to add
		// f_it ~ r_itjk binary factors
		// store them so you have something to query for during decode
		Refinements refs = new Refinements("joint-frame-roles");
		List<Factor> factors = new ArrayList<Factor>();
		for(FrameInstanceHypothesis fhyp : inThisSentence) {
			final int i = fhyp.getTargetHeadIdx();
			final int T = fhyp.numFrames();
			for(int ti=0; ti<T; ti++) {
				Frame t = fhyp.getFrame(ti);
				if(t == Frame.nullFrame) continue;
				Var f_it = fhyp.getFrameVar(ti);
				Iterator<RVar> r_iter = fhyp.getRoleVars(ti).getVars();
				while(r_iter.hasNext()) {
					RVar r_itjk = r_iter.next();
					FeatureVector fv = new FeatureVector();
					params.rFeatures.featurize(fv, refs, i, t, r_itjk.j, r_itjk.k, s);
					VarSet vs = new VarSet(f_it, r_itjk.roleVar);
					ExplicitExpFamFactor phi = new ExplicitExpFamFactor(vs);
					int C = vs.calcNumConfigs();
					for(int c=0; c<C; c++) {
						int[] cfg = vs.getVarConfigAsArray(c);
						if(cfg[0] == BinaryVarUtil.boolToConfig(true) && cfg[0] == cfg[1])
							phi.setFeatures(c, fv);
						else
							phi.setFeatures(c, AbstractFeatures.emptyFeatures);
					}
					factors.add(phi);
					fhyp.addJointFactor(ti, phi);	// store for later (decoding)
				}
			}
		}

		return factors;
	}

	@Override
	public List<Features> getFeatures() {
		List<Features> f = new ArrayList<Features>();
		f.add(params.fFeatures);
		return f;
	}

}
