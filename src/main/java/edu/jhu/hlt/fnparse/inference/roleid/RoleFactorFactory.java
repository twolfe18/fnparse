package edu.jhu.hlt.fnparse.inference.roleid;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.ProjDepTreeFactor.LinkVar;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.features.BinaryBinaryFactorHelper;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.features.Refinements;
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
	public final boolean includeExpansionBinaryFactor;
	public final BinaryBinaryFactorHelper.Mode depFactorMode;
	
	/**
	 * @param params
	 * @param includeDepFactors include binary r_itjk ~ l_jm factors
	 * @param includeGovFactors include binary r_itjk ~ l_mj factors
	 */
	public RoleFactorFactory(ParserParams params, BinaryBinaryFactorHelper.Mode depFactorMode, boolean includeExpansionBinaryFactor) {
		this.params = params;
		this.depFactorMode = depFactorMode;
		this.includeExpansionBinaryFactor = includeExpansionBinaryFactor;

		if(!params.useLatentDepenencies)
			assert depFactorMode == BinaryBinaryFactorHelper.Mode.NONE;
	}
	
	
	private static class RoleDepObservedFeatures implements BinaryBinaryFactorHelper.ObservedFeatures {
		private ParserParams params;
		private String refinement;
		
		public RoleDepObservedFeatures(ParserParams params, String ref) {
			this.params = params;
			this.refinement = ref;
		}
		
		private Sentence sent;
		private int i, j, k;
		private Frame t;
		
		public void set(Sentence sent, int i, Frame t, int j, int k) {
			this.sent = sent;
			this.i = i;
			this.t = t;
			this.j = j;
			this.k = k;
		}

		@Override
		public FeatureVector getObservedFeatures(Refinements r) {
			r = Refinements.product(r, this.refinement, 1d);
			FeatureVector fv = new FeatureVector();
			params.rFeatures.featurize(fv, r, i, t, j, k, sent);
			params.fFeatures.featurize(fv, r, i, t, sent);
			return fv;
		}
	}
	
	
	/**
	 * instantiates the following factors:
	 * r_itjk ~ 1
	 * r_itjk^e ~ 1
	 * r_itjk ~ l_ij
	 * r_itjk ~ r_itjk^e
	 * 
	 * TODO create an Exactly1 factor for r_{i,t,j=*,k}
	 */
	@Override
	public List<Factor> initFactorsFor(Sentence s, List<RoleVars> fr, ProjDepTreeFactor l) {

		RoleDepObservedFeatures feats = new RoleDepObservedFeatures(params, "r_itjk~l_ij");
		BinaryBinaryFactorHelper bbfh = new BinaryBinaryFactorHelper(depFactorMode, feats);
		
		List<Factor> factors = new ArrayList<Factor>();
		for(RoleVars rv : fr) {

			final int i = rv.i;
			final Frame t = rv.getFrame();

			// loop over the (unpruned) role vars
			Iterator<RVar> it = rv.getVars();
			while(it.hasNext()) {
				RVar rvar = it.next();
				
				// r_itjk ~ 1
				FeatureVector fv = new FeatureVector();
				params.rFeatures.featurize(fv, Refinements.noRefinements, i, t, rvar.j, rvar.k, s);
				ExplicitExpFamFactor phi = new ExplicitExpFamFactor(new VarSet(rvar.roleVar));
				phi.setFeatures(BinaryVarUtil.boolToConfig(true), fv);
				phi.setFeatures(BinaryVarUtil.boolToConfig(false), AbstractFeatures.emptyFeatures);
				factors.add(phi);

				// r_itjk^e ~ 1
				if(rvar.expansionVar != null) {
					phi = new ExplicitExpFamFactor(new VarSet(rvar.expansionVar));
					for(int ei=0; ei<rvar.expansionValues.size(); ei++) {
						fv = new FeatureVector();
						Span arg = rvar.expansionValues.get(ei).upon(rvar.j);
						params.reFeatures.featurize(fv, Refinements.noRefinements, i, t, rvar.j, rvar.k, arg, s);
						phi.setFeatures(ei, fv);
					}
					factors.add(phi);
				}

				// r_itjk ~ l_ij
				// this is the only factor which introduces loops
				if(params.useLatentDepenencies && depFactorMode != BinaryBinaryFactorHelper.Mode.NONE) {
					feats.set(s, i, t, rvar.j, rvar.k);
					if(rvar.j < s.size() && rvar.j != i) {	// j==sent.size means "not realized argument"
						LinkVar link = l.getLinkVar(i, rvar.j);
						assert link != null : "i=" + i + ", j=" + rvar.j + ", n=" + s.size();
						VarSet vs = new VarSet(rvar.roleVar, link);
						phi = bbfh.getFactor(vs);
						assert phi != null;
						factors.add(phi);
					}
				}

				// r_itjk ~ r_itjk^e
				if(rvar.expansionVar != null && this.includeExpansionBinaryFactor) {
					VarSet vs = new VarSet(rvar.roleVar, rvar.expansionVar);
					phi = new ExplicitExpFamFactor(vs);
					int C = vs.calcNumConfigs();
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
		if(params.useLatentDepenencies && depFactorMode != BinaryBinaryFactorHelper.Mode.NONE)
			features.add(params.fFeatures);
		if(this.includeExpansionBinaryFactor)
			features.add(params.reFeatures);
		return features;
	}
}
