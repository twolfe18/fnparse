package edu.jhu.hlt.fnparse.features;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import travis.Vector;
import edu.jhu.hlt.fnparse.util.Frame;
import edu.jhu.hlt.fnparse.util.Sentence;

public class BasicTargetFeatures implements TargetFeature {

	// TODO replace with Alphabet
	private Map<String, Integer> featIdx = new HashMap<String, Integer>();
	private List<String> revFeatIdx = new ArrayList<String>();
	
	@Override
	public String getDescription() { return "BasicTargetFeatures"; }

	@Override
	public String getFeatureName(int i) {
		if(i >= revFeatIdx.size() || i < 0)
			throw new RuntimeException(String.format("i=%d featDim=%d", i, revFeatIdx.size()));
		return getDescription() + ":" + revFeatIdx.get(i);
	}
	
	@Override
	public Vector getFeatures(Frame f, int targetIdx, Sentence s) {
		Vector v = Vector.sparse();
		
		v.add(index("null-bias"), f == Frame.NULL_FRAME ? 1d : 0d);
		
		v.add(index("target-pos=" + s.getPos(targetIdx)), 1d);
		
		String hypLU = String.format("%s.%s",
				s.getWord(targetIdx),
				s.getPos(targetIdx).charAt(0));
		boolean matchesAnLU = false;
		int n = f.numLexicalUnits();
		for(int i=0; i<n && !matchesAnLU; i++)
			matchesAnLU |= hypLU.equalsIgnoreCase(f.getLexicalUnit(i));
		v.add(index("LU-match"), matchesAnLU ? 1d : 0d);
		
		return v;
	}
	
	private int index(String featureName) {
		Integer i = featIdx.get(featureName);
		if(i == null) {
			i = featIdx.size();
			featIdx.put(featureName, i);
		}
		return i;
	}
	
	public int cardinality() { return 3; }

}
