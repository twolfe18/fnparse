package edu.jhu.hlt.fnparse.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.util.Alphabet;

/**
 * let T be the class that is extending this class.
 * @author travis
 */
public abstract class AbstractFeatures<T extends AbstractFeatures<?>> {

	public static final LexicalUnit luStart = new LexicalUnit("<S>", "<S>");
	public static final LexicalUnit luEnd = new LexicalUnit("</S>", "</S>");
	
	public static LexicalUnit getLUSafe(int i, Sentence s) {
		if(i < 0) return luStart;
		if(i >= s.size()) return luEnd;
		return s.getLU(i);
	}
	
	public static final FeatureVector emptyFeatures = new FeatureVector();
	
	protected Alphabet<String> featIdx;
	private List<String> refinements;
	private List<Double> weights;
	
	public AbstractFeatures(Alphabet<String> featIdx) {
		this.featIdx = featIdx;
	}
	
	public Alphabet<String> getFeatureAlph() { return featIdx; }
	
	/**
	 * by default, nothing is excluded from regularization,
	 * but you are free to override this.
	 */
	public List<Integer> dontRegularize() {
		return Collections.<Integer>emptyList();
	}
	
	/**
	 * adds a refinement to this feature function.
	 * returns itself for syntactic convenience
	 * e.g. "new FooFeatures().withRefinement("bar", 0.5);"
	 */
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

	/**
	 * all feature names are prefixed with this string.
	 * default implementation is class name.
	 */
	public String getName() {
		return this.getClass().getName();
	}
	
	protected final int b(FeatureVector fv, String... featureNamePieces) {
		return b(fv, 1d, featureNamePieces);
	}
	
	/**
	 * returns the index of the feature being added.
	 * if there are refinements, those indices will not be returned.
	 */
	protected final int b(FeatureVector fv, double weight, String... featureNamePieces) {
		
		int idx = -1;
		StringBuilder sn = new StringBuilder();
		sn.append(getName());
		for(String fns : featureNamePieces) {
			sn.append("_");
			sn.append(fns);
		}
		String s = sn.toString();
		if(featIdx.isGrowing()) {
			int sz = featIdx.size();
			idx = featIdx.lookupIndex(s, true);
			if(sz > 2 * 1000 * 1000 && idx == sz && sz % 100000 == 0)
				System.out.println("[AbstractFeatures b] alph just grew to " + sz);
			fv.add(idx, weight);
		}
		else {
			idx = featIdx.lookupIndex(s, false);
			if(idx >= 0) fv.add(idx, weight);
			//else System.out.println("[AbstractFeatures b] unseen feature: " + s);
		}
		
		if(refinements != null) {
			int c = refinements.size();
			for(int i=0; i<c; i++)
				fv.add(featIdx.lookupIndex(s + "_" + refinements.get(i), true),
						weight * weights.get(i));
		}
		
		return idx;
	}
}
