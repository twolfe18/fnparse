package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;

public final class BasicRoleSpanFeatures extends AbstractFeatures<BasicRoleSpanFeatures> implements Features.RE {

	private static final long serialVersionUID = 1L;

	private ParserParams params;
	
	private boolean aroundSpan = true;
	private boolean fromHead = true;
	private boolean inSpan = false;
	
	public BasicRoleSpanFeatures(ParserParams params) {
		super(params.featIdx);
		this.params = params;
	}


	@Override
	public void featurize(FeatureVector v, Refinements refs, int targetHeadIdx, Frame f, int argHeadIdx, int roleIdx, Span argSpan, Sentence sent) {

		if(argSpan == Span.nullSpan)
			return;
		
		String r = f == Frame.nullFrame
				? "null-frame"
				: (params.fastFeatNames ? String.valueOf(f.getId()) : f.getName());
		String rr = f == Frame.nullFrame
				? "role-for-null-frame"
				: (params.fastFeatNames
						? f.getId() + "." + roleIdx
						: f.getName() + "." + f.getRole(roleIdx));
		
		b(v, refs, 2d, "intercept");
		b(v, refs, 0.5d, r, "intercept");
		b(v, refs, rr, "intercept");
		
		if(argHeadIdx < targetHeadIdx)
			b(v, refs, 3d, "arg-is-left-of-target");
		if(argHeadIdx > targetHeadIdx)
			b(v, refs, 3d, "arg-is-right-of-target");
		if(argHeadIdx == targetHeadIdx)
			b(v, refs, 3d, "arg-is-target");
		
		// TODO features that count number of intermediate POS between arg and target

		b(v, refs, 2d, "width=", String.valueOf(argSpan.width()));
		b(v, refs, 2d, "width/2=", String.valueOf(argSpan.width()/2));
		b(v, refs, 2d, "width/3=", String.valueOf(argSpan.width()/3));
		b(v, refs, 2d, "width/4=", String.valueOf(argSpan.width()/4));
		b(v, refs, 0.5d, r, "width=", String.valueOf(argSpan.width()));
		b(v, refs, 0.5d, r, "width/2=", String.valueOf(argSpan.width()/2));
		b(v, refs, 0.5d, r, "width/3=", String.valueOf(argSpan.width()/3));
		b(v, refs, 0.5d, r, "width/4=", String.valueOf(argSpan.width()/4));
		b(v, refs, rr, "width=", String.valueOf(argSpan.width()));
		b(v, refs, rr, "width/2=", String.valueOf(argSpan.width()/2));
		b(v, refs, rr, "width/3=", String.valueOf(argSpan.width()/3));
		b(v, refs, rr, "width/4=", String.valueOf(argSpan.width()/4));
		
		long p = Math.round((10d * argSpan.width()) / sent.size());
		b(v, refs, 2d, "propWidth=" + p);
		b(v, refs, r, "propWidth=" + p);
		b(v, refs, rr, "propWidth=" + p);
		

		int expLeft = argHeadIdx - argSpan.start;
		assert expLeft >= 0;
		int expRight = argSpan.end - argHeadIdx - 1;
		assert expRight >= 0;
		String er = String.valueOf(expRight);
		String el = String.valueOf(expLeft);
		b(v, refs, "expandRight", er);
		b(v, refs, "expandLeft", el);
		b(v, refs, 0.5d, r, "expandRight", er);
		b(v, refs, 0.5d, r, "expandLeft", el);
		b(v, refs, 2d, rr, "expandRight", er);
		b(v, refs, 2d, rr, "expandLeft", el);
		
		
		// words on either side of the expansion
		if(aroundSpan) {
			LexicalUnit s = AbstractFeatures.getLUSafe(argSpan.start-1, sent);
			b(v, refs, "oneLeft", s.word);
			b(v, refs, "oneLeft", s.pos);
			b(v, refs, r, "oneLeft", s.word);
			b(v, refs, r, "oneLeft", s.pos);
			b(v, refs, 2d, rr, "oneLeft", s.word);
			b(v, refs, 2d, rr, "oneLeft", s.pos);

			LexicalUnit ss = AbstractFeatures.getLUSafe(argSpan.start-2, sent);
			b(v, refs, "twoLeft", ss.word);
			b(v, refs, "twoLeft", ss.pos);
			b(v, refs, r, "twoLeft", ss.word);
			b(v, refs, r, "twoLeft", ss.pos);
			b(v, refs, 2d, rr, "twoLeft", ss.word);
			b(v, refs, 2d, rr, "twoLeft", ss.pos);

			LexicalUnit e = AbstractFeatures.getLUSafe(argSpan.end, sent);
			b(v, refs, "oneRight", e.word);
			b(v, refs, "oneRight", e.pos);
			b(v, refs, r, "oneRight", e.word);
			b(v, refs, r, "oneRight", e.pos);
			b(v, refs, 2d, rr, "oneRight", e.word);
			b(v, refs, 2d, rr, "oneRight", e.pos);

			LexicalUnit ee = AbstractFeatures.getLUSafe(argSpan.end+1, sent);
			b(v, refs, "twoRight", ee.word);
			b(v, refs, "twoRight", ee.pos);
			b(v, refs, r, "twoRight", ee.word);
			b(v, refs, r, "twoRight", ee.pos);
			b(v, refs, 2d, rr, "twoRight", ee.word);
			b(v, refs, 2d, rr, "twoRight", ee.pos);
		}
		
		// words included in the expansion left and right (from the head word)
		if(fromHead) {
			for(int i=argHeadIdx; i>=argSpan.start; i--) {
				LexicalUnit x = sent.getLU(i);
				String sh = "head<-" + (argHeadIdx - i);
				b(v, refs, sh, x.word);
				b(v, refs, sh, x.pos);
				b(v, refs, r, sh, x.word);
				b(v, refs, r, sh, x.pos);
				b(v, refs, 2d, rr, sh, x.word);
				b(v, refs, 2d, rr, sh, x.pos);
			}
			for(int i=argHeadIdx; i<argSpan.end; i++) {
				LexicalUnit x = sent.getLU(i);
				String sh = "head->" + (i - argHeadIdx);
				b(v, refs, sh, x.word);
				b(v, refs, sh, x.pos);
				b(v, refs, r, sh, x.word);
				b(v, refs, r, sh, x.pos);
				b(v, refs, 2d, rr, sh, x.word);
				b(v, refs, 2d, rr, sh, x.pos);
			}
		}
		
		// words in the span
		if(inSpan) {
			for(int i=argSpan.start; i<argSpan.end; i++) {

				LexicalUnit x = sent.getLU(i);
				String si = String.valueOf(i - argSpan.start) + "->";
				String ei = "<-" + String.valueOf(argSpan.end - i - 1);

				b(v, refs, si, x.word);
				b(v, refs, si, x.pos);
				b(v, refs, ei, x.word);
				b(v, refs, ei, x.pos);
				b(v, refs, r, si, x.word);
				b(v, refs, r, si, x.pos);
				b(v, refs, r, ei, x.word);
				b(v, refs, r, ei, x.pos);
				b(v, refs, 2d, rr, si, x.word);
				b(v, refs, 2d, rr, si, x.pos);
				b(v, refs, 2d, rr, ei, x.word);
				b(v, refs, 2d, rr, ei, x.pos);

				b(v, refs, "contains", x.word);
				b(v, refs, "contains", x.pos);
				b(v, refs, r, "contains", x.word);
				b(v, refs, r, "contains", x.pos);
				b(v, refs, 2d, rr, "contains", x.word);
				b(v, refs, 2d, rr, "contains", x.pos);
			}
		}
		
	}

}
