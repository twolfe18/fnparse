package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.misc.Parser.ParserParams;

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
	public FeatureVector getFeatures(Frame f, int targetHeadIdx, int roleIdx, int argHeadIdx, Span argSpan, Sentence sent) {
		
		if(argSpan == Span.nullSpan)
			return emptyFeatures;
		
		FeatureVector v = new FeatureVector();
		
		String r = f == Frame.nullFrame
				? "null-frame"
				: (params.fastFeatNames ? String.valueOf(f.getId()) : f.getName());
		String rr = f == Frame.nullFrame
				? "role-for-null-frame"
				: (params.fastFeatNames
						? f.getId() + "." + roleIdx
						: f.getName() + "." + f.getRole(roleIdx));
		
		b(v, 2d, "intercept");
		b(v, 0.5d, r, "intercept");
		b(v, rr, "intercept");
		
		if(argHeadIdx < targetHeadIdx)
			b(v, 3d, "arg-is-left-of-target");
		if(argHeadIdx > targetHeadIdx)
			b(v, 3d, "arg-is-right-of-target");
		if(argHeadIdx == targetHeadIdx)
			b(v, 3d, "arg-is-target");
		
		// TODO features that count number of intermediate POS between arg and target

		b(v, 2d, "width=", String.valueOf(argSpan.width()));
		b(v, 2d, "width/2=", String.valueOf(argSpan.width()/2));
		b(v, 2d, "width/3=", String.valueOf(argSpan.width()/3));
		b(v, 2d, "width/4=", String.valueOf(argSpan.width()/4));
		b(v, 0.5d, r, "width=", String.valueOf(argSpan.width()));
		b(v, 0.5d, r, "width/2=", String.valueOf(argSpan.width()/2));
		b(v, 0.5d, r, "width/3=", String.valueOf(argSpan.width()/3));
		b(v, 0.5d, r, "width/4=", String.valueOf(argSpan.width()/4));
		b(v, rr, "width=", String.valueOf(argSpan.width()));
		b(v, rr, "width/2=", String.valueOf(argSpan.width()/2));
		b(v, rr, "width/3=", String.valueOf(argSpan.width()/3));
		b(v, rr, "width/4=", String.valueOf(argSpan.width()/4));
		
		long p = Math.round((10d * argSpan.width()) / sent.size());
		b(v, 2d, "propWidth=" + p);
		b(v, r, "propWidth=" + p);
		b(v, rr, "propWidth=" + p);
		

		int expLeft = argHeadIdx - argSpan.start;
		assert expLeft >= 0;
		int expRight = argSpan.end - argHeadIdx - 1;
		assert expRight >= 0;
		String er = String.valueOf(expRight);
		String el = String.valueOf(expLeft);
		b(v, "expandRight", er);
		b(v, "expandLeft", el);
		b(v, 0.5d, r, "expandRight", er);
		b(v, 0.5d, r, "expandLeft", el);
		b(v, 2d, rr, "expandRight", er);
		b(v, 2d, rr, "expandLeft", el);
		
		
		// words on either side of the expansion
		if(aroundSpan) {
			LexicalUnit s = AbstractFeatures.getLUSafe(argSpan.start-1, sent);
			b(v, "oneLeft", s.word);
			b(v, "oneLeft", s.pos);
			b(v, r, "oneLeft", s.word);
			b(v, r, "oneLeft", s.pos);
			b(v, 2d, rr, "oneLeft", s.word);
			b(v, 2d, rr, "oneLeft", s.pos);

			LexicalUnit ss = AbstractFeatures.getLUSafe(argSpan.start-2, sent);
			b(v, "twoLeft", ss.word);
			b(v, "twoLeft", ss.pos);
			b(v, r, "twoLeft", ss.word);
			b(v, r, "twoLeft", ss.pos);
			b(v, 2d, rr, "twoLeft", ss.word);
			b(v, 2d, rr, "twoLeft", ss.pos);

			LexicalUnit e = AbstractFeatures.getLUSafe(argSpan.end, sent);
			b(v, "oneRight", e.word);
			b(v, "oneRight", e.pos);
			b(v, r, "oneRight", e.word);
			b(v, r, "oneRight", e.pos);
			b(v, 2d, rr, "oneRight", e.word);
			b(v, 2d, rr, "oneRight", e.pos);

			LexicalUnit ee = AbstractFeatures.getLUSafe(argSpan.end+1, sent);
			b(v, "twoRight", ee.word);
			b(v, "twoRight", ee.pos);
			b(v, r, "twoRight", ee.word);
			b(v, r, "twoRight", ee.pos);
			b(v, 2d, rr, "twoRight", ee.word);
			b(v, 2d, rr, "twoRight", ee.pos);
		}
		
		// words included in the expansion left and right (from the head word)
		if(fromHead) {
			for(int i=argHeadIdx; i>=argSpan.start; i--) {
				LexicalUnit x = sent.getLU(i);
				String sh = "head<-" + (argHeadIdx - i);
				b(v, sh, x.word);
				b(v, sh, x.pos);
				b(v, r, sh, x.word);
				b(v, r, sh, x.pos);
				b(v, 2d, rr, sh, x.word);
				b(v, 2d, rr, sh, x.pos);
			}
			for(int i=argHeadIdx; i<argSpan.end; i++) {
				LexicalUnit x = sent.getLU(i);
				String sh = "head->" + (i - argHeadIdx);
				b(v, sh, x.word);
				b(v, sh, x.pos);
				b(v, r, sh, x.word);
				b(v, r, sh, x.pos);
				b(v, 2d, rr, sh, x.word);
				b(v, 2d, rr, sh, x.pos);
			}
		}
		
		// words in the span
		if(inSpan) {
			for(int i=argSpan.start; i<argSpan.end; i++) {

				LexicalUnit x = sent.getLU(i);
				String si = String.valueOf(i - argSpan.start) + "->";
				String ei = "<-" + String.valueOf(argSpan.end - i - 1);

				b(v, si, x.word);
				b(v, si, x.pos);
				b(v, ei, x.word);
				b(v, ei, x.pos);
				b(v, r, si, x.word);
				b(v, r, si, x.pos);
				b(v, r, ei, x.word);
				b(v, r, ei, x.pos);
				b(v, 2d, rr, si, x.word);
				b(v, 2d, rr, si, x.pos);
				b(v, 2d, rr, ei, x.word);
				b(v, 2d, rr, ei, x.pos);

				b(v, "contains", x.word);
				b(v, "contains", x.pos);
				b(v, r, "contains", x.word);
				b(v, r, "contains", x.pos);
				b(v, 2d, rr, "contains", x.word);
				b(v, 2d, rr, "contains", x.pos);
			}
		}
		
		return v;
	}

}
