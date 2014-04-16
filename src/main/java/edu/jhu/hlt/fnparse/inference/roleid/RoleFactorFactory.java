package edu.jhu.hlt.fnparse.inference.roleid;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.features.*;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars.RVar;

/**
 * all features that look at a role variable should be housed here.
 * instantiate factors that concatenate features rather than have multiple
 * factors because this is more efficient for BP.
 * 
 * @author travis
 */
public final class RoleFactorFactory implements FactorFactory<RoleVars> {
	
	private static final long serialVersionUID = 1L;
	
	public final ParserParams params;
	public final boolean includeGovFactors;
	public final boolean includeDepFactors;
	public final boolean includeExpansionBinaryFactor;

	/**
	 * @param params
	 * @param includeDepFactors include binary r_itjk ~ l_jm factors
	 * @param includeGovFactors include binary r_itjk ~ l_mj factors
	 */
	public RoleFactorFactory(ParserParams params, boolean includeDepFactors, boolean includeGovFactors, boolean includeExpansionBinaryFactor) {
		this.params = params;
		this.includeDepFactors = includeDepFactors;
		this.includeGovFactors = includeGovFactors;
		this.includeExpansionBinaryFactor = includeExpansionBinaryFactor;
	}
	
	/**
	 * instantiates the following factors:
	 * r_itjk ~ 1
	 * r_itjk^e ~ 1
	 * r_itjk ~ r_itjk^e
	 * r_itjk ~ l_*j
	 * r_itjk ~ l_j*
	 * 
	 * TODO create an Exactly1 factor for r_{i,t,j=*,k}
	 */
	@Override
	public List<Factor> initFactorsFor(Sentence s, List<RoleVars> fr, ProjDepTreeFactor l) {
		
		FeatureVector fv;
		ExplicitExpFamFactor phi;
		final int n = s.size();
		List<Factor> factors = new ArrayList<Factor>();
		for(RoleVars rv : fr) {

			final int i = rv.i;
			final Frame t = rv.getFrame();

			// loop over the (unpruned) role vars
			Iterator<RVar> it = rv.getVars();
			while(it.hasNext()) {
				RVar rvar = it.next();
				
				// r_itjk ~ 1
				fv = new FeatureVector();
				params.rFeatures.featurize(fv, Refinements.noRefinements, i, t, rvar.j, rvar.k, s);
				phi = new ExplicitExpFamFactor(new VarSet(rvar.roleVar));
				phi.setFeatures(BinaryVarUtil.boolToConfig(true), fv);
				phi.setFeatures(BinaryVarUtil.boolToConfig(false), AbstractFeatures.emptyFeatures);
				factors.add(phi);

				// r_itjk^e ~ 1
				phi = new ExplicitExpFamFactor(new VarSet(rvar.expansionVar));
				for(int ei=0; ei<rvar.expansionValues.size(); ei++) {
					fv = new FeatureVector();
					Span arg = rvar.expansionValues.get(ei).upon(rvar.j);
					params.reFeatures.featurize(fv, Refinements.noRefinements, i, t, rvar.j, rvar.k, arg, s);
					phi.setFeatures(ei, fv);
				}
				factors.add(phi);

				// r_itjk ~ l_*j
				if(this.includeGovFactors) {
					Refinements r = new Refinements("gov-of-r");
					for(int m=-1; m<n; m++) {
						if(m == rvar.j) continue; 
						fv = new FeatureVector();
						params.rdFeatures.featurize(fv, r, i, t, rvar.j, rvar.k, m, s);
						VarSet vs = new VarSet(rvar.roleVar, l.getLinkVar(m, rvar.j));
						phi = new ExplicitExpFamFactor(vs);
						phi.setFeatures(BinaryVarUtil.boolToConfig(true), fv);
						phi.setFeatures(BinaryVarUtil.boolToConfig(false), AbstractFeatures.emptyFeatures);
						factors.add(phi);
					}
				}

				// r_itjk ~ l_j*
				if(this.includeDepFactors) {
					Refinements r = new Refinements("dep-of-r");
					for(int m=0; m<n; m++) {
						if(m == rvar.j) continue; 
						fv = new FeatureVector();
						params.rdFeatures.featurize(fv, r, i, t, rvar.j, rvar.k, m, s);
						VarSet vs = new VarSet(rvar.roleVar, l.getLinkVar(rvar.j, m));
						phi = new ExplicitExpFamFactor(vs);
						phi.setFeatures(BinaryVarUtil.boolToConfig(true), fv);
						phi.setFeatures(BinaryVarUtil.boolToConfig(false), AbstractFeatures.emptyFeatures);
						factors.add(phi);
					}
				}
				
				// r_itjk ~ r_itjk^e
				if(this.includeExpansionBinaryFactor) {
					VarSet vs = new VarSet(rvar.roleVar, rvar.expansionVar);
					int C = vs.calcNumConfigs();
					phi = new ExplicitExpFamFactor(vs);
					for(int c=0; c<C; c++) {
						VarConfig conf = vs.getVarConfig(c);
						boolean argRealized = BinaryVarUtil.configToBool(conf.getState(rvar.roleVar));
						if(argRealized) {
							int ei = conf.getState(rvar.roleVar);
							Span arg = rvar.expansionValues.get(ei).upon(rvar.j);
							fv = new FeatureVector();
							params.reFeatures.featurize(fv, Refinements.noRefinements, i, t, rvar.j, rvar.k, arg, s);
							phi.setFeatures(c, fv);
						}
						else {
							// i don't think we need to parameterize negative arg configs
							phi.setFeatures(c, AbstractFeatures.emptyFeatures);
						}
						
					}
					factors.add(phi);
				}
				
			}
		}
		return factors;
	}

	@Override
	public List<Features> getFeatures() {
		List<Features> features = new ArrayList<Features>();
		features.add(params.rFeatures);
		features.add(params.reFeatures);
		if(this.includeDepFactors || this.includeGovFactors)
			features.add(params.rdFeatures);
		if(this.includeExpansionBinaryFactor)
			features.add(params.reFeatures);
		return features;
	}
}
