package edu.jhu.hlt.fnparse.features;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.util.Alphabet;

/**
 * let T be the class that is extending this class.
 * @author travis
 */
public abstract class AbstractFeatures<T extends AbstractFeatures<?>> {

	public static final LexicalUnit luStart = new LexicalUnit("<S>", "<S>");
	public static final LexicalUnit luEnd = new LexicalUnit("</S>", "</S>");
	
	public static final FeatureVector emptyFeatures = new FeatureVector();
	
	protected Alphabet<String> featIdx;
	private List<String> refinements;
	private List<Double> weights;
	
	public AbstractFeatures(Alphabet<String> featIdx) {
		this.featIdx = featIdx;
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
	
	protected void b(FeatureVector fv, String featureName) {
		
		String n = getName() + "_";
		String s = n + featureName;
		fv.add(featIdx.lookupIndex(s, true), 1d);
		
		if(refinements != null) {
			int c = refinements.size();
			for(int i=0; i<c; i++) {
				s = n + refinements.get(i) + "_" + featureName;
				fv.add(featIdx.lookupIndex(s, true), weights.get(i));
			}
		}
	}
}
