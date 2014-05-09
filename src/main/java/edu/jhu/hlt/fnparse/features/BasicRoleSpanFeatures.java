package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;

public final class BasicRoleSpanFeatures extends AbstractFeatures<BasicRoleSpanFeatures> implements Features.RE {

	private static final long serialVersionUID = 1L;
	
	private boolean aroundSpan = true;
	private boolean fromHead = true;
	private boolean inSpan = true;
	private boolean betweenTargetAndHead = true;
	
	public BasicRoleSpanFeatures(ParserParams params) {
		super(params);
	}

	// TODO with syntax, have features describing how similar the projection of the dependency tree from j down is to the actual expanded span

	@Override
	public void featurize(FeatureVector v, Refinements refs, int targetHeadIdx, Frame f, int argHeadIdx, int roleIdx, Span argSpan, Sentence sent) {

		if(argSpan == Span.nullSpan)
			return;
		
		String r = f == Frame.nullFrame
				? "role-for-null-frame"
				: f.getRole(roleIdx);
		String rr = f == Frame.nullFrame
				? "role-for-null-frame"
				: (params.fastFeatNames
						? f.getId() + "." + roleIdx
						: f.getName() + "." + f.getRole(roleIdx));
		
		b(v, refs, 5d, "intercept");
		b(v, refs, 3d, r, "intercept");
		b(v, refs, 3d, rr, "intercept");
		
		if(argHeadIdx < targetHeadIdx)
			b(v, refs, 3d, "arg-is-left-of-target");
		if(argHeadIdx > targetHeadIdx)
			b(v, refs, 3d, "arg-is-right-of-target");
		if(argHeadIdx == targetHeadIdx)
			b(v, refs, 3d, "arg-is-target");
		
		int cutoff = 5;
		b(v, refs, 2d, "width=", intTrunc(argSpan.width(), cutoff));
		b(v, refs, 2d, "width/2=", intTrunc(argSpan.width()/2, cutoff));
		b(v, refs, 2d, "width/3=", intTrunc(argSpan.width()/3, cutoff));
		b(v, refs, 2d, "width/4=", intTrunc(argSpan.width()/4, cutoff));
		b(v, refs, r, "width=", intTrunc(argSpan.width(), cutoff));
		b(v, refs, r, "width/2=", intTrunc(argSpan.width()/2, cutoff));
		b(v, refs, r, "width/3=", intTrunc(argSpan.width()/3, cutoff));
		b(v, refs, r, "width/4=", intTrunc(argSpan.width()/4, cutoff));
		b(v, refs, rr, "width=", intTrunc(argSpan.width(), cutoff));
		b(v, refs, rr, "width/2=", intTrunc(argSpan.width()/2, cutoff));
		b(v, refs, rr, "width/3=", intTrunc(argSpan.width()/3, cutoff));
		b(v, refs, rr, "width/4=", intTrunc(argSpan.width()/4, cutoff));
		
		long p = Math.round((10d * argSpan.width()) / sent.size());
		b(v, refs, 2d, "propWidth=" + p);
		b(v, refs, 2d, r, "propWidth=" + p);
		b(v, refs, 2d, rr, "propWidth=" + p);
		

		int expLeft = argHeadIdx - argSpan.start;
		assert expLeft >= 0;
		int expRight = argSpan.end - argHeadIdx - 1;
		assert expRight >= 0;
		String er = intTrunc(expRight, 6);
		String el = intTrunc(expLeft, 6);
		String er2 = intTrunc(expRight/2, 4);
		String el2 = intTrunc(expLeft/2, 4);
		double w = 0.5d;
		b(v, refs, w, "expandRight", er);
		b(v, refs, w, "expandLeft", el);
		b(v, refs, w, "expandRight/2", er2);
		b(v, refs, w, "expandLeft/2", el2);
		w = 1d;
		b(v, refs, w, r, "expandRight", er);
		b(v, refs, w, r, "expandLeft", el);
		b(v, refs, w, r, "expandRight/2", er2);
		b(v, refs, w, r, "expandLeft/2", el2);
		w = 1.5d;
		b(v, refs, w, rr, "expandRight", er);
		b(v, refs, w, rr, "expandLeft", el);
		b(v, refs, w, rr, "expandRight/2", er2);
		b(v, refs, w, rr, "expandLeft/2", el2);
		LexicalUnit headLemmaLU = sent.getLemmaLU(argHeadIdx);
		w = 1.5d;
		b(v, refs, w, headLemmaLU.pos, "expandRight", er);
		b(v, refs, w, headLemmaLU.pos, "expandLeft", el);
		b(v, refs, w, headLemmaLU.pos, "expandRight/2", er2);
		b(v, refs, w, headLemmaLU.pos, "expandLeft/2", el2);
		w = 0.5d;
		b(v, refs, w, headLemmaLU.pos, r, "expandRight", er);
		b(v, refs, w, headLemmaLU.pos, r, "expandLeft", el);
		b(v, refs, w, headLemmaLU.pos, r, "expandRight/2", er2);
		b(v, refs, w, headLemmaLU.pos, r, "expandLeft/2", el2);
//		w = 0.2d;
//		b(v, refs, w, headLemmaLU.pos, rr, "expandRight", er);
//		b(v, refs, w, headLemmaLU.pos, rr, "expandLeft", el);
//		b(v, refs, w, headLemmaLU.pos, rr, "expandRight/2", er2);
//		b(v, refs, w, headLemmaLU.pos, rr, "expandLeft/2", el2);

		
		if(argSpan.includes(targetHeadIdx)) {
			b(v, refs, 3d, "arg-overlaps-targetHead");
			b(v, refs, 2d, r, "arg-overlaps-targetHead");
			b(v, refs, 2d, rr, "arg-overlaps-targetHead");
		}

		// features that count number of intermediate POS between arg and target
		if(betweenTargetAndHead) {
			if(targetHeadIdx < argHeadIdx) {
				for(int i=targetHeadIdx+1; i<argHeadIdx; i++) {
					b(v, refs, r, "between-target-and-arg", sent.getPos(i));
					b(v, refs, rr, "between-target-and-arg", sent.getPos(i));
					b(v, refs, 0.3d, r, "between-target-and-arg", sent.getLemma(i));
					b(v, refs, 0.1d, rr, "between-target-and-arg", sent.getLemma(i));
				}
			}
			else if(argHeadIdx < targetHeadIdx) {
				for(int i=argHeadIdx+1; i<targetHeadIdx; i++) {
					b(v, refs, r, "between-target-and-arg", sent.getPos(i));
					b(v, refs, rr, "between-target-and-arg", sent.getPos(i));
					b(v, refs, 0.3d, r, "between-target-and-arg", sent.getLemma(i));
					b(v, refs, 0.1d, rr, "between-target-and-arg", sent.getLemma(i));
				}
			}
		}

		
		// words on either side of the expansion
		if(aroundSpan) {
			double sml = 0.25d;
			double med = 0.5d;
			double reg = 0.75d;
			double lrg = 1d;

			LexicalUnit s = AbstractFeatures.getLUSafe(argSpan.start-1, sent);
			b(v, refs, reg, "oneLeft", s.word);
			b(v, refs, lrg, "oneLeft", s.pos);
			b(v, refs, reg, r, "oneLeft", s.word);
			b(v, refs, lrg, r, "oneLeft", s.pos);
			b(v, refs, med, rr, "oneLeft", s.word);
			b(v, refs, reg, rr, "oneLeft", s.pos);

			LexicalUnit ss = AbstractFeatures.getLUSafe(argSpan.start-2, sent);
			b(v, refs, med, "twoLeft", ss.word);
			b(v, refs, reg, "twoLeft", ss.pos);
			b(v, refs, med, r, "twoLeft", ss.word);
			b(v, refs, reg, r, "twoLeft", ss.pos);
			b(v, refs, sml, rr, "twoLeft", ss.word);
			b(v, refs, med, rr, "twoLeft", ss.pos);

			LexicalUnit e = AbstractFeatures.getLUSafe(argSpan.end, sent);
			b(v, refs, reg, "oneRight", e.word);
			b(v, refs, lrg, "oneRight", e.pos);
			b(v, refs, reg, r, "oneRight", e.word);
			b(v, refs, lrg, r, "oneRight", e.pos);
			b(v, refs, med, rr, "oneRight", e.word);
			b(v, refs, reg, rr, "oneRight", e.pos);

			LexicalUnit ee = AbstractFeatures.getLUSafe(argSpan.end+1, sent);
			b(v, refs, med, "twoRight", ee.word);
			b(v, refs, reg, "twoRight", ee.pos);
			b(v, refs, med, r, "twoRight", ee.word);
			b(v, refs, reg, r, "twoRight", ee.pos);
			b(v, refs, sml, rr, "twoRight", ee.word);
			b(v, refs, med, rr, "twoRight", ee.pos);
		}
		
		// words included in the expansion left and right (from the head word)
		if(fromHead) {
			for(int i=argHeadIdx; i>=argSpan.start; i--) {
				LexicalUnit x = sent.getLemmaLU(i);
				String sh = "head<-" + intTrunc(argHeadIdx - i, 3);
				b(v, refs, sh, x.word);
				b(v, refs, sh, x.pos);
				b(v, refs, r, sh, x.word);
				b(v, refs, r, sh, x.pos);
				b(v, refs, rr, sh, x.word);
				b(v, refs, rr, sh, x.pos);
			}
			for(int i=argHeadIdx; i<argSpan.end; i++) {
				LexicalUnit x = sent.getLemmaLU(i);
				String sh = "head->" + intTrunc(i - argHeadIdx, 3);
				b(v, refs, sh, x.word);
				b(v, refs, sh, x.pos);
				b(v, refs, r, sh, x.word);
				b(v, refs, r, sh, x.pos);
				b(v, refs, rr, sh, x.word);
				b(v, refs, rr, sh, x.pos);
			}
		}
		
		// words in the span (measured from left and right)
		if(inSpan) {
			double sml = 0.2d;
			double med = 0.6d;
			double reg = 1d;
			double lrg = 1.3d;
			for(int i=argSpan.start; i<argSpan.end; i++) {

				LexicalUnit x = sent.getLemmaLU(i);
				String si = intTrunc(i - argSpan.start, 3) + "->";
				String ei = "<-" + intTrunc(argSpan.end - i - 1, 3);

				b(v, refs, sml, si, x.word);
				b(v, refs, sml, si, x.pos);
				b(v, refs, sml, ei, x.word);
				b(v, refs, sml, ei, x.pos);
				b(v, refs, med, r, si, x.word);
				b(v, refs, med, r, si, x.pos);
				b(v, refs, med, r, ei, x.word);
				b(v, refs, med, r, ei, x.pos);
				b(v, refs, sml, rr, si, x.word);
				b(v, refs, sml, rr, si, x.pos);
				b(v, refs, sml, rr, ei, x.word);
				b(v, refs, sml, rr, ei, x.pos);

				b(v, refs, reg, "contains", x.word);
				b(v, refs, reg, "contains", x.pos);
				b(v, refs, lrg, r, "contains", x.word);
				b(v, refs, lrg, r, "contains", x.pos);
				b(v, refs, lrg, rr, "contains", x.word);
				b(v, refs, lrg, rr, "contains", x.pos);
			}
		}
		
	}
	
}
