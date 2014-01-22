package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.util.Alphabet;

public class BasicTargetFeatures implements FrameFeatures {

	private Alphabet<String> featIdx = new Alphabet<String>();
	
	@Override
	public String getDescription() { return "BasicTargetFeatures"; }

	@Override
	public String getFeatureName(int i) {
		String localName = featIdx.lookupObject(i);
		return getDescription() + ":" + localName;
	}
	
	public int head(Span s) {
		if(s.width() == 1)
			return s.start;
		else {
			System.err.println("warning! implement a real head finder");
			return s.end-1;
		}
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, Span extent, Sentence s) {
		
		int head = head(extent);
		
		FeatureVector v = new FeatureVector();
		
		v.add(index("null-bias"), f == Frame.nullFrame ? 1d : 0d);
		
		v.add(index("target-pos=" + s.getPos(head)), 1d);
		
		String hypLU = String.format("%s.%s",
				s.getWord(head),
				s.getPos(head).charAt(0));
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
