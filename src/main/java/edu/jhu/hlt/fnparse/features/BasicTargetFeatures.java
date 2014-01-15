package edu.jhu.hlt.fnparse.features;

import java.util.HashMap;
import java.util.Map;

import travis.Vector;
import edu.jhu.hlt.fnparse.util.Frame;
import edu.jhu.hlt.fnparse.util.Sentence;

public class BasicTargetFeatures implements TargetFeature {

	private Map<String, Integer> featIdx = new HashMap<String, Integer>();
	
	@Override
	public String getName() { return "BasicTargetFeatures"; }

	@Override
	public Vector getFeatures(Frame f, int targetIdx, Sentence s) {
		Vector v = Vector.sparse();
		
		v.add(index("null-bias"), f == Frame.NULL_FRAME ? 1d : 0d);
		
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

}
