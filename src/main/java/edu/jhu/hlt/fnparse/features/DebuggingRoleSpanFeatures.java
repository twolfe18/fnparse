package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.util.Alphabet;

public class DebuggingRoleSpanFeatures extends AbstractFeatures<DebuggingRoleSpanFeatures> implements Features.RE {

	private static final long serialVersionUID = 1L;

	public DebuggingRoleSpanFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}

	@Override
	public FeatureVector getFeatures(Frame f, int targetHeadIdx, int argHeadIdx, int roleIdx, Span argSpan, Sentence s) {
		FeatureVector fv = new FeatureVector();
		b(fv, f.getName(), String.valueOf(targetHeadIdx), String.valueOf(roleIdx), argSpan.toString());
		b(fv, f.getName(), String.valueOf(targetHeadIdx), String.valueOf(roleIdx));
		return fv;
	}

}
