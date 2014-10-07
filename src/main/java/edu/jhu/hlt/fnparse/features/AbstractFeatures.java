package edu.jhu.hlt.fnparse.features;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import org.apache.commons.math3.util.FastMath;
import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.HasParserParams;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.util.Alphabet;

/**
 * Parent class of all features, includes some useful code to be shared.
 * Let T be the class that is extending this class.
 * 
 * @author travis
 */
public abstract class AbstractFeatures<T extends AbstractFeatures<?>>
		implements Serializable, HasFeatureAlphabet {
	private static final long serialVersionUID = 1L;

	// NOTE: This can produce a LOT of output, so only use for debugging
	public static boolean PRINT_UNSEEN_FEATURES = false;

	public static final FeatureVector emptyFeatures = new FeatureVector();
	public static final LexicalUnit luStart = new LexicalUnit("<S>", "<S>");
	public static final LexicalUnit luEnd = new LexicalUnit("</S>", "</S>");

	protected transient Logger log;

	public static LexicalUnit getLUSafe(int i, Sentence s) {
		if (i < 0)
			return luStart;
		if (i >= s.size())
			return luEnd;
		return s.getLU(i);
	}

	// If true, use frame id instead of frame name so feature names are shorter
	// NOTE: DO NOT use role id instead of role name because role name is
	// consistent across frames, and need not be conjoined with the frame (always).
	protected boolean useFastFeaturenames = false;

	// Often features are given with a certain weight, which mimics the effect
	// of a non-uniformly regularized model. For example I may not want to
	// regularize the intercept at all, which can be (in-exactly) accomplished
	// by giving it a much larger weight. This can go awry though, and I've seen
	// so in my tests. There are a few factors that go into play with this:
	// 1) How strong the regularizer is
	// 2) How common the feature is
	// 3) How large the weight on the feature is
	// 4) The discrepancy between training loss (log-loss) and test loss (e.g. F1)
	// I'm not going to go into careful detail about how this can occur, but
	// I've tried removing the weights completely and this fixed a problem I was
	// having, so I will just assume that this is a potential problem.
	// 
	// This parameter is basically an annealing paramter to use or ignore the
	// weights that are added at callsite. This is a global hammer, which may or
	// may not be a good thing, but I added it late.
	// 0 => features have uniform weight
	// 1 => features have the weight given at callsite
	// i.e. weight_final = pow(weight_callsite, weightingPower)
	protected double weightingPower = 1d;

	protected boolean debug = false;

	protected final HasParserParams globalParams;

	public AbstractFeatures(HasParserParams globalParams) {
		this.globalParams = globalParams;
	}

	@Override
	public Alphabet<String> getAlphabet() {
		return globalParams.getParserParams().getAlphabet();
	}

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

	private String name = null;
	/**
	 * all feature names are prefixed with this string.
	 * default implementation is class name.
	 */
	public String getName() {
		if(name == null)
			name = this.getClass().getName().replace("edu.jhu.hlt.fnparse.features.", "");
		return name;
	}

	protected final void b(FeatureVector fv, Refinements refs, String... featureNamePieces) {
		b(fv, refs, 1d, featureNamePieces);
	}

	/**
	 * returns the index of the feature being added.
	 * if there are refinements, those indices will not be returned.
	 */
	protected final void b(
			FeatureVector fv,
			Refinements refs,
			double weight,
			String... featureNamePieces) {
		Alphabet<String> alph = getAlphabet();
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
			if(alph.isGrowing()) {
				int sz = alph.size();
				int idx = alph.lookupIndex(s, true);
				if(sz > 2 * 1000 * 1000 && idx == sz && sz % 200000 == 0)
					getLogger().info("[b] alph just grew to " + sz);
				if (weightingPower == 0d) {
					weight = 1d;
				} else {
					weight *= refs.getWeight(ri);
					if (weightingPower != 1d)
						weight = FastMath.pow(weight, weightingPower);
				}
				fv.add(idx, weight);
			}
			else {
				int idx = alph.lookupIndex(s, false);
				if(idx >= 0) {
					if (weightingPower == 0d) {
						weight = 1d;
					} else {
						weight *= refs.getWeight(ri);
						if (weightingPower != 1d)
							weight = FastMath.pow(weight, weightingPower);
					}
					fv.add(idx, weight);
				} else if (PRINT_UNSEEN_FEATURES) {
					getLogger().debug("[b] unseen feature: " + s);
				}
			}
		}
	}

	private Logger getLogger() {
		if (log == null)
			log = Logger.getLogger(getClass());
		return log;
	}
}
