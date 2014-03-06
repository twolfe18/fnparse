package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.util.Alphabet;

public class ConstituencyFeatures extends AbstractFeatures<ConstituencyFeatures> implements Features.E {
	
	public ConstituencyFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}
	
	@Override
	public FeatureVector getFeatures(Span cons, Sentence sent) {

		if(cons == Span.nullSpan)
			return emptyFeatures;
		
		FeatureVector v = new FeatureVector();
		
		b(v, "intercept");
		b(v, "width=", String.valueOf(cons.width()));
		b(v, "width/2=", String.valueOf(cons.width()/2));
		b(v, "width/3=", String.valueOf(cons.width()/3));
		b(v, "width/4=", String.valueOf(cons.width()/4));
		
		long p = Math.round((10d * cons.width()) / sent.size());
		b(v, "propWidth=" + p);
		
		int s = cons.start;
		if(s > 0) {
			b(v, "oneLeft=", sent.getWord(s-1));
			b(v, "oneLeft=", sent.getPos(s-1));
			if(s > 1) {
				b(v, "twoLeft=", sent.getWord(s-2));
				b(v, "twoLeft=", sent.getPos(s-2));
			}
		}
		
		int e = cons.end;
		if(e < sent.size()) {
			b(v, "oneRight=", sent.getWord(e));
			b(v, "oneRight=", sent.getPos(e));
			if(e < sent.size() - 1) {
				b(v, "twoRight=", sent.getWord(e+1));
				b(v, "twoRight=", sent.getPos(e+1));
			}
		}
		
		for(int i=cons.start; i<cons.end; i++) {
			b(v, "contains", sent.getWord(i));
			b(v, "contains", sent.getPos(i));
		}
		
		return v;
	}
}
