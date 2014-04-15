package edu.jhu.hlt.fnparse.inference.roleid;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.features.*;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars.RVar;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.util.Alphabet;

/**
 * all features that look at a role variable should be housed here.
 * instantiate factors that concatenate features rather than have multiple
 * factors because this is more efficient for BP.
 * 
 * @author travis
 */
public final class RoleFactorFactory extends HasRoleFeatures implements FactorFactory<RoleVars> {
	
	private static final long serialVersionUID = 1L;
	
	public ParserParams params;
	public boolean binaryDepParentFactor = true;
	public boolean binaryDepChildFactor = false;

	public RoleFactorFactory(ParserParams params) {
		super(params.featIdx);
		this.params = params;
		
		if(params.useLatentDepenencies)
			assert binaryDepChildFactor || binaryDepParentFactor;
	}
	
	/**
	 * instantiates the following factors:
	 * r_itjk --$$
	 * r_itjk --$$-- r_itjk^e	TODO
	 * r_itjk^e --$$
	 * r_itjk --$$-- l_*j
	 * r_itjk --$$-- l_j*
	 * 
	 * TODO create an Exactly1 factor for r_{i,t,j=*,k}
	 */
	@Override
	public List<Factor> initFactorsFor(Sentence s, List<RoleVars> fr, ProjDepTreeFactor l) {
		
		if(this.hasNoFeatures())
			return Collections.emptyList();
		
		final int n = s.size();
		
		List<Factor> factors = new ArrayList<Factor>();
		for(RoleVars rv : fr) {

			Frame ft = rv.getFrame();

			// loop over the (unpruned) role vars
			Iterator<RVar> it = rv.getVars();
			while(it.hasNext()) {
				RVar rvar = it.next();
				
				// am i supposed to know the expansion/span value here?
				// maybe, maybe not
				// i cannot share those features across all implementations...
				// maybe i should go back to factors that compute features?
				
				/*
				 * AH, i see
				 * responsibility for what factors get instantiated moved from HasRoleFeatures into this code.
				 * i think i can basically take the features for the statically known data.
				 * do it this way (simple) and maybe add different features later.
				 */
				Span argSpan = Span.widthOne(rvar.j);	// TODO replace this with features that don't look at the argSpan, e-var-factors should be conjoined with width?
				FeatureVector features = super.getFeatures(ft, rv.getTargetHead(), rvar.k, argSpan, rvar.j, s);

				// r_itjk --$$
				factors.add(new F(new VarSet(rvar.roleVar), params.featIdx, new String[] {"r=0", "r=1"}, features));

				// r_itjk --$$-- l_*j
				if(binaryDepParentFactor && params.useLatentDepenencies) {
					String[] configNames = new String[] {"r=0,lp=0", "r=0,lp=1", "r=1,lp=0", "r=1,lp=1"};	// "lp" is for "link parent"
					for(int i=0; i<n; i++) {
						VarSet vs = new VarSet(rvar.roleVar, l.getLinkVar(rvar.j, i));
						factors.add(new F(vs, params.featIdx, configNames, features));
					}
				}

				// r_itjk --$$-- l_j*
				if(binaryDepChildFactor && params.useLatentDepenencies) {
					String[] configNames = new String[] {"r=0,lc=0", "r=0,lc=1", "r=1,lc=0", "r=1,lc=1"};	// "lc" is for "link child"
					for(int i=0; i<n; i++) {
						VarSet vs = new VarSet(rvar.roleVar, l.getLinkVar(rvar.j, i));
						factors.add(new F(vs, params.featIdx, configNames, features));
					}
				}
				
				
				// r_itjk^e --$$
				if(rvar.expansionVar != null)
					factors.add(new FE(rvar.expansionVar, rv.i, rv.t, rvar.j, rvar.k, rvar.expansionValues, s, this));

			}
		}
		return factors;
	}
	
	static final class FE extends ExpFamFactor {


		private static final long serialVersionUID = 1L;

		private Expansion.Iter expansions;
		private int i;
		private Frame t;
		private int j;
		private int k;
		private Sentence sent;
		private Features.RE fRE;
		private Features.E fE;

		public FE(Var expansionVar, int i, Frame t, int j, int k, Expansion.Iter expansions, Sentence sent, HasRoleFeatures features) {
			super(new VarSet(expansionVar));
			this.i = i;
			this.t = t;
			this.j = j;
			this.k = k;
			this.sent = sent;
			this.fE = features.eFeatures;
			this.fRE = features.reFeatures;
			this.expansions = expansions;
		}
		
		@Override
		public FeatureVector getFeatures(int config) {
			Expansion e = expansions.get(config);
			Span s = e.upon(j);
			FeatureVector fv = new FeatureVector();
			if(fRE != null)
				fv.add(fRE.getFeatures(t, i, k, j, s, sent));
			if(fE != null)
				fv.add(fE.getFeatures(s, sent));
			return fv;
		}
	}
	
	static class F extends ExpFamFactor {

		private static final long serialVersionUID = 3383011899524311722L;

		private Alphabet<String> alph;
		private String[] specifics;
		private FeatureVector features;

		public F(VarSet needed, Alphabet<String> alph, String[] specifics, FeatureVector base) {
			super(needed);
			this.alph = alph;
			this.specifics = specifics;
			this.features = base;
		}

		@Override
		public FeatureVector getFeatures(int config) {
			// TODO maybe cache these conjoins?
			
			// this should be faster
			//if(config == 0)
			//	return AbstractFeatures.emptyFeatures;

			return conjoin(features, specifics[config], alph);
		}

		/** take a feature vector and conjoin each of its features with a string like "f=0,r=1" or "r=0,l=1" or "r=1,e=1:3" */
		public static FeatureVector conjoin(final FeatureVector base, final String specific, final Alphabet<String> featureNames) {
			final FeatureVector fv = new FeatureVector();
			base.apply(new FnIntDoubleToDouble() {
				@Override
				public double call(int idx, double val) {
					String fullFeatName = specific + ":" + featureNames.lookupObject(idx);
					int newIdx = featureNames.lookupIndex(fullFeatName, true);
					fv.add(newIdx, val);
					return val;
				}
			});
			return fv;
		}
	}

}
