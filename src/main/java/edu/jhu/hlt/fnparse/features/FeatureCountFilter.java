package edu.jhu.hlt.fnparse.features;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.util.Alphabet;



public class FeatureCountFilter {
	
	private int[] counts;
	private int nFG, nF;
	private Map<Class<?>, Integer> ignoredCounts;
	
	public FeatureCountFilter() {
		reset();
	}
	
	public void reset() {
		counts = new int[1024];
		nFG = 0;
		nF = 0;
		ignoredCounts = new HashMap<Class<?>, Integer>();
	}
	
	/**
	 * Make a new alphabet from only the features that occurred at lead a few times.
	 * 
	 * @param featureNames should include all the features in vectors passed into observe()
	 * @param minFeatureOccurrences
	 * @param keepRegardless may be null, otherwise feature names in this set will be included regardless of their count
	 * @return
	 */
	public Alphabet<String> filterByCount(Alphabet<String> featureNames, int minFeatureOccurrences, Alphabet<String> keepRegardless) {
		Alphabet<String> keep = new Alphabet<String>();
		int n = featureNames.size();
		this.keep = new BitSet(n);
		for(int i=0; i<n; i++) {
			String fn = featureNames.lookupObject(i);
			if(counts[i] >= minFeatureOccurrences || keepRegardless.lookupIndex(fn, false) >= 0) {
				keep.lookupIndex(fn, true);
				this.keep.set(i);
			}
		}
		if(keepRegardless != null && keepRegardless.size() > 0) {
			System.out.printf("[FeatureCountFilter] after inspecting %d FgExmples and given %d features to keep regardless, "
					+ "%d features are in the resulting map because they either appeared %d times or were pre-existing\n",
					nFG, keepRegardless.size(), keep.size(), minFeatureOccurrences);
		}
		else {
			System.out.printf("[FeatureCountFilter] after inspecting %d FgExmples, found %d of %d features appeared at least %d times\n",
					nFG, keep.size(), featureNames.size(), minFeatureOccurrences);
		}
		System.out.println("[FeatureCountFilter] " + nF + " factors checked, skipped: " + ignoredCounts);
		return keep;
	}

	public void observe(FgExample instance) {
		nFG++;
		for(Factor f : instance.getFgLatPred().getFactors()) {
			nF++;
			if(f instanceof ExplicitExpFamFactor) {
				ExplicitExpFamFactor ef = (ExplicitExpFamFactor) f;
				int C = ef.getVars().calcNumConfigs();
				for(int c=0; c<C; c++)
					count(ef.getFeatures(c));
			}
			else {
				Class<?> key = f.getClass();
				Integer c = ignoredCounts.get(key);
				if(c == null) c = 0;
				ignoredCounts.put(key, c + 1);
			}
		}
	}
	
	private BitSet keep = null;	// set in filterByCount()

	/**
	 * removes features that have been pruned by a previous call to this.filterByCount().
	 */
	public void prune(FgExample instance) {

/*
 * When we train, we compute features, count how many times they appeared,
 * and then prune the features that don't show up at least K times. Given that
 * we computed the features to count them anyway, it is a waste to throw them
 * away, but we need to remove the feature-values for the features that didn't
 * show up at least K times. This class removes those features.
 */

		if(keep == null)
			throw new IllegalStateException("you have to call this.filterByCount() before you call this method");
		
		for(FactorGraph fg : Arrays.asList(instance.getFgLat(), instance.getFgLatPred())) {
			for(Factor f : fg.getFactors()) {
				if(f instanceof ExplicitExpFamFactor) {
					ExplicitExpFamFactor ef = (ExplicitExpFamFactor) f;
					int C = ef.getVars().calcNumConfigs();
					for(int c=0; c<C; c++) {
						ef.getFeatures(c).apply(new FnIntDoubleToDouble() {	// only keep indices in the bitset
							@Override
							public double call(int idx, double val) {
								return keep.get(idx) ? val : 0d;
							}
						});
					}
				}
				
				// I don't think i need to prune the clamped versions because i think they
				// point to the original factor which will be pruned
				//else System.err.println("[FeatureCountFilter] f=" + f.getClass());
			}
		}
	}
	
	private void count(FeatureVector fv) {
		fv.compact();
		fv.apply(new FnIntDoubleToDouble() {
			@Override
			public double call(int idx, double val) {
				if(idx >= counts.length)
					grow(idx + 1);
				counts[idx]++;
				return val;
			}
		});
	}
	
	private void grow(int minSize) {
		int newSize = Math.max(minSize, (int) (counts.length * 1.4) + 10);
		counts = Arrays.copyOf(counts, newSize);
	}
}
