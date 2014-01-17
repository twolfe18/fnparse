package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.util.Alphabet;

public class BasicTargetFeatures implements TargetFeatures {

	private Alphabet<String> featIdx = new Alphabet<String>();
	private Frame nullFrame;
	
	public BasicTargetFeatures(Frame nullFrame) {
		this.nullFrame = nullFrame;
	}
	
	@Override
	public String getDescription() { return "BasicTargetFeatures"; }

	@Override
	public String getFeatureName(int i) {
		String localName = featIdx.lookupObject(i);
		return getDescription() + ":" + localName;
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, int targetIdx, Sentence s) {
		FeatureVector v = new FeatureVector();
		
		v.add(index("null-bias"), f == this.nullFrame ? 1d : 0d);
		
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
		return featIdx.lookupIndex(featureName, true);
	}
	
	public int cardinality() { return 3; }

}
