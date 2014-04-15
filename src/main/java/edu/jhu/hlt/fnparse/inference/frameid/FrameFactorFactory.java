package edu.jhu.hlt.fnparse.inference.frameid;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance.Prototype;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.features.HasFrameFeatures;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.util.Alphabet;

/**
 * Instantiates factors that touch f_it.
 * 
 * @author travis
 */
public final class FrameFactorFactory extends HasFrameFeatures implements FactorFactory<FrameVars> {

	private static final long serialVersionUID = 1L;
	
	private boolean frameDepFactors;
	private boolean depParents = true;
	private boolean depChildren = true;
	
	private Alphabet<String> featIdx;	// TODO remove this
	
	public FrameFactorFactory(ParserParams params) {
		this.frameDepFactors = params.useLatentDepenencies;
		this.featIdx = params.featIdx;
	}
	
	@Override
	public String toString() { return "<FrameFactorFactory>"; }

	// TODO need to add an Exactly1 factor to each FrameVars
	@Override
	public List<Factor> initFactorsFor(Sentence s, List<FrameVars> fr, ProjDepTreeFactor l) {
		final int n = s.size();
		List<Factor> factors = new ArrayList<Factor>();
		for(FrameVars fhyp : fr) {
			final int T = fhyp.numFrames();
			final int i = fhyp.getTargetHeadIdx();
			for(int t=0; t<T; t++) {
				Frame f = fhyp.getFrame(t);
				Prototype p = null;
				VarSet vs = new VarSet(fhyp.getVariable(t));
				FeatureVector features = getFeatures(f, p, i, s);
				factors.add(new F(vs, features));
				
				// binary factors that touch a f_it variable and an l_ij variable
				if(frameDepFactors) {
					for(int j=-1; j<n; j++) {
						if(depParents) {
							Var link = l.getLinkVar(j, i);
							if(link == null) continue;
							FrameDepFactor fdf = new FrameDepFactor(fhyp.getVariable(t), i, f, link, true, j);
							fdf.setState(featIdx, s);
							factors.add(fdf);
						}
						if(depChildren && j >= 0) {
							Var link = l.getLinkVar(i, j);
							if(link == null) continue;
							FrameDepFactor fdf = new FrameDepFactor(fhyp.getVariable(t), i, f, link, false, j);
							fdf.setState(featIdx, s);
							factors.add(fdf);
						}
					}
				}
			}
		}
		return factors;
	}
	

	static final class F extends ExpFamFactor {

		private static final long serialVersionUID = 1L;

		private FeatureVector features;

		public F(VarSet vars, FeatureVector features) {
			super(vars);
			if(vars.size() != 1)
				throw new IllegalArgumentException("shouldn't this have just the frame var?");
			this.features = features;
		}

		@Override
		public FeatureVector getFeatures(int config) {
			if(BinaryVarUtil.configToBool(config))
				return features;
			return AbstractFeatures.emptyFeatures;
		}
		
	}
}

