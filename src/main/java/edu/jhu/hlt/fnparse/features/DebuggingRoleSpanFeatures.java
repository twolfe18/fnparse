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
	public void featurize(FeatureVector fv, Refinements r, int targetHeadIdx, Frame f, int argHeadIdx, int roleIdx, Span argSpan, Sentence s) {

		String aLemma = argHeadIdx >= s.size() ? "null" : s.getLemma(argHeadIdx);
		String aPOS = argHeadIdx >= s.size() ? "nullPOS" : s.getPos(argHeadIdx);
		String fs = f.getName();
		String rs = f.getRole(roleIdx);
		
		b(fv, r, fs, String.valueOf(targetHeadIdx), rs, argSpan.toString());
		b(fv, r, fs, String.valueOf(targetHeadIdx), rs);
		b(fv, r, rs);
		b(fv, r, fs, rs);
		b(fv, r, fs, rs, aLemma);
		b(fv, r, fs, rs, aPOS);
		
		if(argHeadIdx < targetHeadIdx) {
			b(fv, r, fs, rs, "arg-left");
			b(fv, r, fs, "arg-left");
			b(fv, r, "arg-left");
		}
		if(argHeadIdx > targetHeadIdx) {
			b(fv, r, fs, rs, "arg-right");
			b(fv, r, fs, "arg-right");
			b(fv, r, "arg-right");
		}
	}

}
