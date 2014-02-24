package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.util.Alphabet;

public class DebuggingConstituencyFeatures extends AbstractFeatures<DebuggingConstituencyFeatures> implements Features.E {
	
	public DebuggingConstituencyFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}
	
	@Override
	public FeatureVector getFeatures(Span constituent, Sentence s) {
		
		if(constituent == Span.nullSpan)
			return emptyFeatures;
		
		FeatureVector fv = new FeatureVector();
		b(fv, "width=" + constituent.width() + "_sent=" + s.getId());
		return fv;
	}
	
}
