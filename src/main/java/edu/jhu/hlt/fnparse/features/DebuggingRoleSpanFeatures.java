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

		if(r != Refinements.noRefinements)
			throw new RuntimeException("implement me (in AbstractFeatures.b)!");		

		String aLemma = argHeadIdx >= s.size() ? "null" : s.getLemma(argHeadIdx);
		String aPOS = argHeadIdx >= s.size() ? "nullPOS" : s.getPos(argHeadIdx);
		String fs = f.getName();
		String rs = f.getRole(roleIdx);
		
		b(fv, fs, String.valueOf(targetHeadIdx), rs, argSpan.toString());
		b(fv, fs, String.valueOf(targetHeadIdx), rs);
		b(fv, rs);
		b(fv, fs, rs);
		b(fv, fs, rs, aLemma);
		b(fv, fs, rs, aPOS);
		
		if(argHeadIdx < targetHeadIdx) {
			b(fv, fs, rs, "arg-left");
			b(fv, fs, "arg-left");
			b(fv, "arg-left");
		}
		if(argHeadIdx > targetHeadIdx) {
			b(fv, fs, rs, "arg-right");
			b(fv, fs, "arg-right");
			b(fv, "arg-right");
		}
	}

}
