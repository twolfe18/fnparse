package edu.jhu.hlt.fnparse.features;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.util.Alphabet;

/**
 * let T be the class that is extending this class.
 * @author travis
 */
public abstract class AbstractFeatures<T extends AbstractFeatures<?>> implements Serializable {

	private static final long serialVersionUID = 1L;
	
	public static final LexicalUnit luStart = new LexicalUnit("<S>", "<S>");
	public static final LexicalUnit luEnd = new LexicalUnit("</S>", "</S>");
	
	public static LexicalUnit getLUSafe(int i, Sentence s) {
		if(i < 0) return luStart;
		if(i >= s.size()) return luEnd;
		return s.getLU(i);
	}
	
	public static final FeatureVector emptyFeatures = new FeatureVector();
	
	protected ParserParams params;
	
	public AbstractFeatures(ParserParams params) {
		this.params = params;
	}
	
	public Alphabet<String> getFeatureAlph() { return params.featIdx; }
	
	/**
	 * by default, nothing is excluded from regularization,
	 * but you are free to override this.
	 */
	public List<Integer> dontRegularize() {
		return Collections.emptyList();
	}


	public static final String intTrunc(int value, int max) {
		assert value >= 0;
		assert max >= 0;
		if(value > max)
			return (max+1) + "+";
		else
			return String.valueOf(value);
	}

	
	/*
	 * adds a refinement to this feature function.
	 * returns itself for syntactic convenience
	 * e.g. "new FooFeatures().withRefinement("bar", 0.5);"
	@SuppressWarnings("unchecked")
	public T withRefinement(String name, double weight) {
		if(this.refinements == null) {
			this.refinements = new ArrayList<String>();
			this.weights = new ArrayList<Double>();
		}
		this.refinements.add(name);
		this.weights.add(weight);
		return (T) this;
	}
	 */

	/**
	 * all feature names are prefixed with this string.
	 * default implementation is class name.
	 */
	public String getName() {
		return this.getClass().getName().replace("edu.jhu.hlt.fnparse.features.", "");
	}
	
	protected final void b(FeatureVector fv, Refinements refs, String... featureNamePieces) {
		b(fv, refs, 1d, featureNamePieces);
	}
	
	/**
	 * returns the index of the feature being added.
	 * if there are refinements, those indices will not be returned.
	 */
	protected final void b(FeatureVector fv, Refinements refs, double weight, String... featureNamePieces) {
		
		Alphabet<String> featIdx = params.featIdx;
		int rs = refs.size();
		for(int ri=0; ri<rs; ri++) {
			StringBuilder sn = new StringBuilder();
			sn.append(getName());
			if(refs != Refinements.noRefinements) {
				sn.append("@");
				sn.append(refs.getName(ri));
			}
			for(String fns : featureNamePieces) {
				sn.append("_");
				sn.append(fns);
			}
			String s = sn.toString();
			if(featIdx.isGrowing()) {
				int sz = featIdx.size();
				int idx = featIdx.lookupIndex(s, true);
				if(sz > 2 * 1000 * 1000 && idx == sz && sz % 200000 == 0)
					System.out.println("[AbstractFeatures b] alph just grew to " + sz);
				fv.add(idx, weight * refs.getWeight(ri));
			}
			else {
				int idx = featIdx.lookupIndex(s, false);
				if(idx >= 0) fv.add(idx, weight * refs.getWeight(ri));
				//else System.out.println("[AbstractFeatures b] unseen feature: " + s);
			}
		}
	}

	/* take a feature vector and conjoin each of its features with a string like "f=0,r=1" or "r=0,l=1" or "r=1,e=1:3"
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
	*/
}
