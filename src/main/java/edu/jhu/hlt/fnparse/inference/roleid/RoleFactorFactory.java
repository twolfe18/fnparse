package edu.jhu.hlt.fnparse.inference.roleid;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.ProjDepTreeFactor.LinkVar;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.BasicRoleFeatures;
import edu.jhu.hlt.fnparse.features.BinaryBinaryFactorHelper;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.features.Refinements;
import edu.jhu.hlt.fnparse.features.BinaryBinaryFactorHelper.Mode;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars.RVar;

/**
 * should really be called RoleIdFactorFactory (this is not a FactorFactory for RoleSpanStage)
 * 
 * @author travis
 */
public final class RoleFactorFactory implements FactorFactory<RoleVars> {
	
	private static final long serialVersionUID = 1L;

	/**
	 * this is an adapter needed for BinaryBinaryFactorHelper
	 * 
	 * @author travis
	 */
	private static class RoleDepObservedFeatures implements BinaryBinaryFactorHelper.ObservedFeatures {

		private static final long serialVersionUID = 1L;

		private String refinement;
		private Features.R rFeats;
		private Features.F fFeats;
		
		public RoleDepObservedFeatures(ParserParams params, String ref) {
			this.refinement = ref;
			this.rFeats = new BasicRoleFeatures(params);
			this.fFeats = new BasicFrameFeatures(params);
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
			rFeats.featurize(fv, r, i, t, j, k, sent);
			fFeats.featurize(fv, r, i, t, sent);
			assert fv.getNumImplicitEntries() > 0 : "did you use the right alphabet?";
			return fv;
		}
	}
	
	
	public static interface FactorMode {
		public BinaryBinaryFactorHelper.Mode depFactorMode();
		public BinaryBinaryFactorHelper.Mode govFactorMode();
	}
	
	
	public Features.R rFeats;
	public RoleDepObservedFeatures feats;
	public BinaryBinaryFactorHelper bbfh;
	public Refinements r_itjk_unaryRef = new Refinements("r_itjk~1");
	public FgModel weights;
	public final ParserParams params;
	
	/**
	 * @param params
	 * @param factorMode says how the (r_itjk ~ l_ij) factor should be parameterized
	 */
	public RoleFactorFactory(ParserParams params, BinaryBinaryFactorHelper.Mode factorMode) {
		this.rFeats = new BasicRoleFeatures(params);
		feats = new RoleDepObservedFeatures(params, "r_itjk~l_ij");
		bbfh = new BinaryBinaryFactorHelper(factorMode, feats);
		this.params = params;
	}
	
	/**
	 * instantiates the following factors:
	 * r_itjk ~ 1
	 * r_itjk^e ~ 1
	 * r_itjk ~ l_ij
	 * r_itjk ~ r_itjk^e
	 */
	@Override
	public List<Factor> initFactorsFor(Sentence s, List<RoleVars> fr, ProjDepTreeFactor l, ConstituencyTreeFactor c) {
		
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
				rFeats.featurize(fv, r_itjk_unaryRef, i, t, rvar.j, rvar.k, s);
				ExplicitExpFamFactor phi = new ExplicitExpFamFactor(new VarSet(rvar.roleVar));
				phi.setFeatures(BinaryVarUtil.boolToConfig(true), fv);
				phi.setFeatures(BinaryVarUtil.boolToConfig(false), AbstractFeatures.emptyFeatures);
				if(weights != null)
					phi.updateFromModel(weights, params.logDomain);
				factors.add(phi);

//				// r_itjk^e ~ 1
//				if(rvar.expansionVar != null && !params.predictHeadValuedArguments) {
//					phi = new ExplicitExpFamFactor(new VarSet(rvar.expansionVar));
//					for(int ei=0; ei<rvar.expansionValues.size(); ei++) {
//						fv = new FeatureVector();
//						Span arg = rvar.expansionValues.get(ei).upon(rvar.j);
//						params.reFeatures.featurize(fv, Refinements.noRefinements, i, t, rvar.j, rvar.k, arg, s);
//						phi.setFeatures(ei, fv);
//					}
//					factors.add(phi);
//				}

				// r_itjk ~ l_ij
				if(params.useLatentDepenencies && bbfh.getMode() != Mode.NONE) {
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
//				if(rvar.expansionVar != null && this.includeExpansionBinaryFactor && !params.predictHeadValuedArguments) {
//					VarSet vs = new VarSet(rvar.roleVar, rvar.expansionVar);
//					phi = new ExplicitExpFamFactor(vs);
//					int C = vs.calcNumConfigs();
//					for(int c=0; c<C; c++) {
//						VarConfig conf = vs.getVarConfig(c);
//						boolean argRealized = BinaryVarUtil.configToBool(conf.getState(rvar.roleVar));
//						if(argRealized) {
//							int ei = conf.getState(rvar.roleVar);
//							Span arg = rvar.expansionValues.get(ei).upon(rvar.j);
//							fv = new FeatureVector();
//							params.reFeatures.featurize(fv, Refinements.noRefinements, i, t, rvar.j, rvar.k, arg, s);
//							phi.setFeatures(c, fv);
//						}
//						else {
//							// i don't think we need to parameterize negative arg configs
//							phi.setFeatures(c, AbstractFeatures.emptyFeatures);
//						}
//						
//					}
//					factors.add(phi);
//				}
				
			}
		}
		return factors;
	}

	@Override
	public List<Features> getFeatures() {
		List<Features> features = new ArrayList<Features>();
		features.add(rFeats);
		return features;
	}
}
