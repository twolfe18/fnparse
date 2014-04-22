package edu.jhu.hlt.fnparse.features;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
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
	
	public Alphabet<String> filterByCount(Alphabet<String> featureNames, int minFeatureOccurrences) {
		Alphabet<String> keep = new Alphabet<String>();
		int n = featureNames.size();
		for(int i=0; i<n; i++) {
			if(counts[i] < minFeatureOccurrences)
				continue;
			String fn = featureNames.lookupObject(i);
			keep.lookupIndex(fn, true);
		}
		System.out.printf("[FeatureCountFilter] after inspecting %d FgExmples, found %d of %d features appeared at least %d times\n",
				nFG, keep.size(), featureNames.size(), minFeatureOccurrences);
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
	
	private void count(FeatureVector fv) {
		fv.compact();
		fv.apply(new FnIntDoubleToDouble() {
			@Override
			public double call(int idx, double val) {
				if(idx >= counts.length)
					grow();
				counts[idx]++;
				return -1;
			}
		});
	}
	
	private void grow() {
		int newSize = (int) (counts.length * 1.4) + 10;
		counts = Arrays.copyOf(counts, newSize);
	}
}
