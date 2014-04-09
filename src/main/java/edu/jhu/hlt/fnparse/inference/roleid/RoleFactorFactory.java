package edu.jhu.hlt.fnparse.inference.roleid;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.features.*;
import edu.jhu.hlt.fnparse.inference.misc.FactorFactory;
import edu.jhu.hlt.fnparse.inference.misc.Parser;
import edu.jhu.hlt.fnparse.inference.misc.Parser.ParserParams;
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
	public boolean binaryDepParentFactor;
	public boolean binaryDepChildFactor;
	//public boolean binaryFrameFactor;

	public RoleFactorFactory(ParserParams params) {
		super(params.featIdx);
		this.params = params;
	}
	
	/**
	 * instantiates the following factors:
	 * r_itjk --$$
	 * r_itjk --$$-- f_it
	 * r_itjk --$$-- l_*j
	 * r_itjk --$$-- l_j*
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
				FeatureVector features = super.getFeatures(ft, rv.getTargetHead(), rvar.k, null, rvar.j, s);

				// r_itjk --$$
				factors.add(new FF(new VarSet(rvar.var), params.featIdx, new String[] {"r=0", "r=1"}, features));

				// r_itjk --$$-- l_*j
				if(binaryDepParentFactor) {
					String[] configNames = new String[] {"r=0,lp=0", "r=0,lp=1", "r=1,lp=0", "r=1,lp=1"};	// "lp" is for "link parent"
					for(int i=0; i<n; i++) {
						VarSet vs = new VarSet(rvar.var, l.getLinkVar(rvar.j, i));
						factors.add(new FF(vs, params.featIdx, configNames, features));
					}
				}

				// r_itjk --$$-- l_j*
				if(binaryDepChildFactor) {
					String[] configNames = new String[] {"r=0,lc=0", "r=0,lc=1", "r=1,lc=0", "r=1,lc=1"};	// "lc" is for "link child"
					for(int i=0; i<n; i++) {
						VarSet vs = new VarSet(rvar.var, l.getLinkVar(rvar.j, i));
						factors.add(new FF(vs, params.featIdx, configNames, features));
					}
				}

				/* this has to move. JointFrameRoleId should have a module that serves as its FactorFactory
				// r_itjk --$$-- f_it
				if(binaryFrameFactor) {
					String[] configNames = new String[] {"r=0,f=0", "r=0,f=1", "r=1,f=0", "r=1,f=1"};
					VarSet vs = new VarSet(rvar.var, fhyp.getFrameVar(t));
					factors.add(new FF(vs, params.featIdx, configNames, features));
				}
				*/
			}
		}
		return factors;
	}
	

	static class FF extends ExpFamFactor {

		private static final long serialVersionUID = 3383011899524311722L;

		private Alphabet<String> alph;
		private String[] specifics;
		private FeatureVector features;

		public FF(VarSet needed, Alphabet<String> alph, String[] specifics, FeatureVector base) {
			super(needed);
			this.alph = alph;
			this.specifics = specifics;
			this.features = base;
		}

		@Override
		public FeatureVector getFeatures(int config) {
			// TODO maybe cache these conjoins?
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
			return base;
		}
	}

}
