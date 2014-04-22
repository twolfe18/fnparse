package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;

public class DebuggingRoleSpanFeatures extends AbstractFeatures<DebuggingRoleSpanFeatures> implements Features.RE {

	private static final long serialVersionUID = 1L;

	public DebuggingRoleSpanFeatures(ParserParams params) {
		super(params);
	}

	@Override
	public void featurize(FeatureVector fv, Refinements r, int targetHeadIdx, Frame f, int argHeadIdx, int roleIdx, Span argSpan, Sentence s) {

		LexicalUnit a = s.getLemmaLU(argHeadIdx);
		String tLoc = "target@" + targetHeadIdx;	// wouldn't include this in non-debugging features, it will clearly overfit
		String fs = "frame=" + f.getName();
		String rs = "role=" + f.getRole(roleIdx);
		String width = "argWidth=" + Math.min(argSpan.width() / 5, 3);
		
		b(fv, r, fs, tLoc, rs, argSpan.toString());
		b(fv, r, fs, tLoc, rs);
		b(fv, r, rs);
		b(fv, r, fs, rs);
		b(fv, r, fs, rs, a.word);
		b(fv, r, fs, rs, a.pos);
		
		b(fv, r, fs, rs, width);
		b(fv, r, rs, width);
		
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
