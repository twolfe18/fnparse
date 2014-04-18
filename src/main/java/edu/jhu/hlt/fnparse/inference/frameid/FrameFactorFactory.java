package edu.jhu.hlt.fnparse.inference.frameid;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.features.Refinements;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;

/**
 * Instantiates factors that touch f_it.
 * 
 * @author travis
 */
public final class FrameFactorFactory implements FactorFactory<FrameVars> {

	private static final long serialVersionUID = 1L;
	
	private ParserParams params;
	private boolean includeDepFactors;
	private boolean includeGovFactors;
	private boolean onlyParemeterizeDiag;
	
	/**
	 * @param params
	 * @param includeDepFactors include binary factors f_it ~ l_ij
	 * @param includeGovFactors include binary factors f_it ~ l_ji
	 */
	public FrameFactorFactory(ParserParams params, boolean includeDepFactors, boolean includeGovFactors) {
		this.params = params;
		this.includeDepFactors = includeDepFactors;
		this.includeGovFactors = includeGovFactors;
		this.onlyParemeterizeDiag = true;
	}
	
	@Override
	public String toString() { return "<FrameFactorFactory>"; }

	// TODO need to add an Exactly1 factor to each FrameVars
	// ^^^^ do i really need this if i'm not doing joint inference?
	@Override
	public List<Factor> initFactorsFor(Sentence s, List<FrameVars> fr, ProjDepTreeFactor l) {
		final int n = s.size();
		List<Factor> factors = new ArrayList<Factor>();
		for(FrameVars fhyp : fr) {
			final int T = fhyp.numFrames();
			final int i = fhyp.getTargetHeadIdx();
			for(int tIdx=0; tIdx<T; tIdx++) {

				Frame t = fhyp.getFrame(tIdx);
				
				// unary factor on f_it
				VarSet vs = new VarSet(fhyp.getVariable(tIdx));
				FeatureVector fv = new FeatureVector();
				params.fFeatures.featurize(fv, Refinements.noRefinements, i, t, s);
				ExplicitExpFamFactor phi = new ExplicitExpFamFactor(vs);
				phi.setFeatures(BinaryVarUtil.boolToConfig(true), fv);
				phi.setFeatures(BinaryVarUtil.boolToConfig(false), AbstractFeatures.emptyFeatures);
				factors.add(phi);
				
				// binary factor f_it ~ l_ji
				if(this.includeGovFactors) {
					for(int j=-1; j<n; j++) {
						if(i == j) continue;
						vs = new VarSet(fhyp.getVariable(tIdx), l.getLinkVar(j, i));
						phi = new ExplicitExpFamFactor(vs);
						Refinements diagRef = new Refinements("parent-of-target");
						fv = null;
						FeatureVector fvInv = null;
						int nv = vs.calcNumConfigs();
						for(int c=0; c<nv; c++) {
							int[] cfg = vs.getVarConfigAsArray(c);

							if(onlyParemeterizeDiag) {
								if(cfg[0] != cfg[1])
									phi.setFeatures(c, AbstractFeatures.emptyFeatures);
								else {
									
									// only need to compute features once
									if(fv == null) {
										fv = new FeatureVector();
										params.fdFeatures.featurize(fv, diagRef, i, t, j, s);
										fvInv = new FeatureVector(fv);
										fvInv.scale(-1d);
									}
									
									if(BinaryVarUtil.configToBool(cfg[0]))
										phi.setFeatures(c, fv);
									else
										phi.setFeatures(c, fvInv);
								}
							}
							else {
								Refinements r = new Refinements("f=" + cfg[0] + ",lg=" + cfg[1]);
								fv = new FeatureVector();
								params.fdFeatures.featurize(fv, r, i, t, j, s);
								phi.setFeatures(c, fv);
							}
						}
						factors.add(phi);
					}
				}
				
				// binary factor f_it ~ l_ij
				if(this.includeDepFactors) {
					for(int j=0; j<n; j++) {
						if(i == j) continue;
						vs = new VarSet(fhyp.getVariable(tIdx), l.getLinkVar(i, j));
						phi = new ExplicitExpFamFactor(vs);
						Refinements diagRef = new Refinements("child-of-target");
						fv = null;
						FeatureVector fvInv = null;
						int nv = vs.calcNumConfigs();
						for(int c=0; c<nv; c++) {
							int[] cfg = vs.getVarConfigAsArray(c);

							if(onlyParemeterizeDiag) {
								if(cfg[0] != cfg[1])
									phi.setFeatures(c, AbstractFeatures.emptyFeatures);
								else {
									
									// only need to compute features once
									if(fv == null) {
										fv = new FeatureVector();
										params.fdFeatures.featurize(fv, diagRef, i, t, j, s);
										fvInv = new FeatureVector(fv);
										fvInv.scale(-1d);
									}
									
									if(BinaryVarUtil.configToBool(cfg[0]))
										phi.setFeatures(c, fv);
									else
										phi.setFeatures(c, fvInv);
								}
							}
							else {
								Refinements r = new Refinements("f=" + cfg[0] + ",ld=" + cfg[1]);
								fv = new FeatureVector();
								params.fdFeatures.featurize(fv, r, i, t, j, s);
								phi.setFeatures(c, fv);
							}
						}
						factors.add(phi);
					}
				}
			}
		}
		return factors;
	}

	@Override
	public List<Features> getFeatures() {
		List<Features> feats = new ArrayList<Features>();
		feats.add(params.fFeatures);
		if(this.includeDepFactors || this.includeGovFactors)
			feats.add(params.fdFeatures);
		return feats;
	}
}

