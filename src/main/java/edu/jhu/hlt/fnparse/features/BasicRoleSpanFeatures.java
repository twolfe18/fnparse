package edu.jhu.hlt.fnparse.features;

import org.apache.commons.math3.util.FastMath;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.HasParserParams;
import edu.jhu.hlt.fnparse.util.PosPatternGenerator;
import edu.jhu.hlt.fnparse.util.PosPatternGenerator.Mode;

public final class BasicRoleSpanFeatures extends AbstractFeatures<BasicRoleSpanFeatures> implements Features.RE {
	private static final long serialVersionUID = 1L;
	public static boolean OVERFITTING_DEBUG = false;

	private boolean posTagSeq = true;
	private boolean aroundSpan = false;
	private boolean fromHead = false;
	private boolean inSpan = true;
	private boolean betweenTargetAndHead = true;
	private boolean headExpansion = false;
	private boolean twoFromBoundary = false;

	// If true, include features that are not conjoined with any role
	// information. This flag is not respected by some simple features like
	// argument span width.
	// Pros:
	// - can generalize to unseen roles
	// Cons:
	// - slow(er)
	// - via feature overlap and regularization, comes with the assumption that
	//   all roles have feature weights centered around some mean
	private boolean roleAgnosticFeatures = false;

	public BasicRoleSpanFeatures(HasParserParams globalParams) {
		super(globalParams);
		weightingPower = 0d;
	}

	// TODO with syntax, have features describing how similar the projection of the dependency tree from j down is to the actual expanded span

	// TODO regular expressions over POS tags

	@Override
	public void featurize(
			FeatureVector v,
			Refinements refs,
			int targetHeadIdx,
			Frame f,
			int argHeadIdx,
			int roleIdx,
			Span argSpan,
			Sentence sent) {
		if (argSpan == Span.nullSpan)
			return;

		String r = f == Frame.nullFrame
				? "role-for-null-frame"
				: f.getRole(roleIdx);
		String rr = f == Frame.nullFrame
				? "role-for-null-frame"
				: (this.useFastFeaturenames
						? f.getId() + "." + roleIdx
						: f.getName() + "." + f.getRole(roleIdx));

		b(v, refs, 3d, "intercept");
		b(v, refs, 3d, r, "intercept");
		b(v, refs, 3d, rr, "intercept");

		if (OVERFITTING_DEBUG) {
			String desc = "target=" + sent.getWord(targetHeadIdx)
					+ ",frame=" + f.getName()
					+ ",head=" + sent.getWord(argHeadIdx)
					+ ",role=" + f.getRole(roleIdx)
					+ ",width=" + argSpan.width();
			b(v, refs, 10d, desc);
			for (int i = 0; i < argSpan.start; i++) {
				int l = argSpan.start - i;
				b(v, refs, 10d, desc + ",arg-" + l + "=" + sent.getWord(i));
			}
			for (int i = argSpan.end; i < sent.size(); i++) {
				int _r = (i - argSpan.end) + 1;
				b(v, refs, 10d, desc + ",arg+" + _r + "=" + sent.getWord(i));
			}
			//return;
		}

		if (argHeadIdx < targetHeadIdx) {
			if (roleAgnosticFeatures)
				b(v, refs, "arg-is-left-of-target");
			b(v, refs, r, "arg-is-left-of-target");
			b(v, refs, rr, "arg-is-left-of-target");
		}
		if (argHeadIdx > targetHeadIdx) {
			if (roleAgnosticFeatures)
				b(v, refs, "arg-is-right-of-target");
			b(v, refs, r, "arg-is-right-of-target");
			b(v, refs, rr, "arg-is-right-of-target");
		}
		if (argHeadIdx == targetHeadIdx) {
			if (roleAgnosticFeatures)
				b(v, refs, "arg-is-target");
			b(v, refs, r, "arg-is-target");
			b(v, refs, rr, "arg-is-target");
		}
		if (argSpan.includes(targetHeadIdx)) {
			if (roleAgnosticFeatures)
				b(v, refs, "arg-overlaps-targetHead");
			b(v, refs, r, "arg-overlaps-targetHead");
			b(v, refs, rr, "arg-overlaps-targetHead");
		}

		int cutoff = 5;
		if (roleAgnosticFeatures) {
			b(v, refs, "width=", intTrunc(argSpan.width(), cutoff));
			b(v, refs, "width/2=", intTrunc(argSpan.width()/2, cutoff));
			b(v, refs, "width/3=", intTrunc(argSpan.width()/3, cutoff));
			b(v, refs, "width/4=", intTrunc(argSpan.width()/4, cutoff));
		}
		//b(v, refs, r, "width=", intTrunc(argSpan.width(), cutoff));
		b(v, refs, r, "width/2=", intTrunc(argSpan.width()/2, cutoff));
		//b(v, refs, r, "width/3=", intTrunc(argSpan.width()/3, cutoff));
		b(v, refs, r, "width/4=", intTrunc(argSpan.width()/4, cutoff));
		//b(v, refs, rr, "width=", intTrunc(argSpan.width(), cutoff));
		b(v, refs, rr, "width/2=", intTrunc(argSpan.width()/2, cutoff));
		//b(v, refs, rr, "width/3=", intTrunc(argSpan.width()/3, cutoff));
		b(v, refs, rr, "width/4=", intTrunc(argSpan.width()/4, cutoff));

		long p = Math.round((10d * argSpan.width()) / sent.size());
		if (roleAgnosticFeatures)
			b(v, refs, "propWidth=" + p);
		b(v, refs, r, "propWidth=" + p);
		b(v, refs, rr, "propWidth=" + p);

		double w;
		if (headExpansion) {
			int expLeft = argHeadIdx - argSpan.start;
			assert expLeft >= 0;
			int expRight = argSpan.end - argHeadIdx - 1;
			assert expRight >= 0;
			String er = intTrunc(expRight, 6);
			String el = intTrunc(expLeft, 6);
			String er2 = intTrunc(expRight/2, 4);
			String el2 = intTrunc(expLeft/2, 4);
			w = 1d;
			if (roleAgnosticFeatures) {
				b(v, refs, w, "expandRight", er);
				b(v, refs, w, "expandLeft", el);
				b(v, refs, w, "expandRight/2", er2);
				b(v, refs, w, "expandLeft/2", el2);
			}
			w = 1d;
			b(v, refs, w, r, "expandRight", er);
			b(v, refs, w, r, "expandLeft", el);
			b(v, refs, w, r, "expandRight/2", er2);
			b(v, refs, w, r, "expandLeft/2", el2);
			w = 1d;
			b(v, refs, w, rr, "expandRight", er);
			b(v, refs, w, rr, "expandLeft", el);
			b(v, refs, w, rr, "expandRight/2", er2);
			b(v, refs, w, rr, "expandLeft/2", el2);
			LexicalUnit headLemmaLU = sent.getLemmaLU(argHeadIdx);
			if (roleAgnosticFeatures) {
				w = 1d;
				b(v, refs, w, headLemmaLU.pos, "expandRight", er);
				b(v, refs, w, headLemmaLU.pos, "expandLeft", el);
				b(v, refs, w, headLemmaLU.pos, "expandRight/2", er2);
				b(v, refs, w, headLemmaLU.pos, "expandLeft/2", el2);
			}
			w = 1d;
			b(v, refs, w, headLemmaLU.pos, r, "expandRight", er);
			b(v, refs, w, headLemmaLU.pos, r, "expandLeft", el);
			b(v, refs, w, headLemmaLU.pos, r, "expandRight/2", er2);
			b(v, refs, w, headLemmaLU.pos, r, "expandLeft/2", el2);
			w = 1d;
			b(v, refs, w, headLemmaLU.pos, rr, "expandRight", er);
			b(v, refs, w, headLemmaLU.pos, rr, "expandLeft", el);
			b(v, refs, w, headLemmaLU.pos, rr, "expandRight/2", er2);
			b(v, refs, w, headLemmaLU.pos, rr, "expandLeft/2", el2);
		}

		if (posTagSeq) {
			// How many tags to the left and right of the argSpan
			int left = 1;
			int right = 1;
			PosPatternGenerator tags =
					new PosPatternGenerator(left, right, Mode.COARSE_POS);
			String tagSeq = tags.extract(argSpan, sent);
			// Give small weight to very long pos tag sequences
			int l = tagSeq.length() - 2*left - 2*right;
			double d = l - 2;
			w = d > 1 ? FastMath.pow(d, -0.5d) : 1d;
			if (w > 0.2) {
				if (roleAgnosticFeatures)
					b(v, refs, w, "tagSeq", tagSeq);
				b(v, refs, w, r, "tagSeq", tagSeq);
				b(v, refs, w, rr, "tagSeq", tagSeq);
			}
		}

		// Features that count number of intermediate POS between arg and target
		if (betweenTargetAndHead) {
			boolean useLemmas = false;
			if (targetHeadIdx < argHeadIdx) {
				for (int i=targetHeadIdx+1; i<argHeadIdx; i++) {
					String pos = sent.getPos(i);
					if (roleAgnosticFeatures)
						b(v, refs, "between-target-and-arg", pos);
					b(v, refs, r, "between-target-and-arg", pos);
					b(v, refs, rr, "between-target-and-arg", pos);
					if (useLemmas) {
						if (roleAgnosticFeatures) {
							b(v, refs, 0.5d,
								"between-target-and-arg", sent.getLemma(i));
						}
						b(v, refs, 0.5d, r,
								"between-target-and-arg", sent.getLemma(i));
						b(v, refs, 0.5d, rr,
								"between-target-and-arg", sent.getLemma(i));
					}
				}
			}
			else if (argHeadIdx < targetHeadIdx) {
				for (int i=argHeadIdx+1; i<targetHeadIdx; i++) {
					String pos = sent.getPos(i);
					if (roleAgnosticFeatures)
						b(v, refs, "between-target-and-arg", pos);
					b(v, refs, r, "between-target-and-arg", pos);
					b(v, refs, rr, "between-target-and-arg", pos);
					if (useLemmas) {
						if (roleAgnosticFeatures) {
							b(v, refs, 0.5d,
								"between-target-and-arg", sent.getLemma(i));
						}
						b(v, refs, 0.5d, r,
								"between-target-and-arg", sent.getLemma(i));
						b(v, refs, 0.5d, rr,
								"between-target-and-arg", sent.getLemma(i));
					}
				}
			}
		}

		// Words on either side of the expansion
		if (aroundSpan) {
			double sml = 0.25d;
			double med = 0.5d;
			double reg = 0.75d;
			double lrg = 1d;

			LexicalUnit s = sent.getLemmaLU(argSpan.start - 1);
			if (roleAgnosticFeatures) {
				b(v, refs, reg, "oneLeft", s.word);
				b(v, refs, lrg, "oneLeft", s.pos);
			}
			b(v, refs, reg, r, "oneLeft", s.word);
			b(v, refs, lrg, r, "oneLeft", s.pos);
			b(v, refs, med, rr, "oneLeft", s.word);
			b(v, refs, reg, rr, "oneLeft", s.pos);

			if (twoFromBoundary) {
				LexicalUnit ss = sent.getLemmaLU(argSpan.start - 2);
				if (roleAgnosticFeatures) {
					b(v, refs, med, "twoLeft", ss.word);
					b(v, refs, reg, "twoLeft", ss.pos);
				}
				b(v, refs, med, r, "twoLeft", ss.word);
				b(v, refs, reg, r, "twoLeft", ss.pos);
				b(v, refs, sml, rr, "twoLeft", ss.word);
				b(v, refs, med, rr, "twoLeft", ss.pos);
			}

			LexicalUnit e = sent.getLemmaLU(argSpan.end);
			if (roleAgnosticFeatures) {
				b(v, refs, reg, "oneRight", e.word);
				b(v, refs, lrg, "oneRight", e.pos);
			}
			b(v, refs, reg, r, "oneRight", e.word);
			b(v, refs, lrg, r, "oneRight", e.pos);
			b(v, refs, med, rr, "oneRight", e.word);
			b(v, refs, reg, rr, "oneRight", e.pos);

			if (twoFromBoundary) {
				LexicalUnit ee = sent.getLemmaLU(argSpan.end + 1);
				if (roleAgnosticFeatures) {
					b(v, refs, med, "twoRight", ee.word);
					b(v, refs, reg, "twoRight", ee.pos);
				}
				b(v, refs, med, r, "twoRight", ee.word);
				b(v, refs, reg, r, "twoRight", ee.pos);
				b(v, refs, sml, rr, "twoRight", ee.word);
				b(v, refs, med, rr, "twoRight", ee.pos);
			}
		}

		// Words included in the expansion left and right (from the head word)
		if (fromHead) {
			for(int i=argHeadIdx; i>=argSpan.start; i--) {
				LexicalUnit x = sent.getLemmaLU(i);
				String sh = "head<-" + intTrunc(argHeadIdx - i, 3);
				if (roleAgnosticFeatures) {
					b(v, refs, sh, x.word);
					b(v, refs, sh, x.pos);
				}
				b(v, refs, r, sh, x.word);
				b(v, refs, r, sh, x.pos);
				b(v, refs, rr, sh, x.word);
				b(v, refs, rr, sh, x.pos);
			}
			for(int i=argHeadIdx; i<argSpan.end; i++) {
				LexicalUnit x = sent.getLemmaLU(i);
				String sh = "head->" + intTrunc(i - argHeadIdx, 3);
				if (roleAgnosticFeatures) {
					b(v, refs, sh, x.word);
					b(v, refs, sh, x.pos);
				}
				b(v, refs, r, sh, x.word);
				b(v, refs, r, sh, x.pos);
				b(v, refs, rr, sh, x.word);
				b(v, refs, rr, sh, x.pos);
			}
		}

		// Words in the span (measured from left and right)
		if (inSpan) {
			boolean includePos = false;
			double sml = 0.2d;
			double med = 0.6d;
			double reg = 1d;
			double lrg = 1.3d;
			for (int i=argSpan.start; i<argSpan.end; i++) {

				LexicalUnit x = sent.getLemmaLU(i);
				String si = intTrunc(i - argSpan.start, 3) + "->";
				String ei = "<-" + intTrunc(argSpan.end - i - 1, 3);

				if (roleAgnosticFeatures) {
					b(v, refs, sml, si, x.word);
					b(v, refs, sml, ei, x.word);
					if (includePos) {
						b(v, refs, sml, si, x.pos);
						b(v, refs, sml, ei, x.pos);
					}
				}
				b(v, refs, med, r, si, x.word);
				b(v, refs, med, r, ei, x.word);
				if (includePos) {
					b(v, refs, med, r, si, x.pos);
					b(v, refs, med, r, ei, x.pos);
				}
				b(v, refs, sml, rr, si, x.word);
				b(v, refs, sml, rr, ei, x.word);
				if (includePos) {
					b(v, refs, sml, rr, si, x.pos);
					b(v, refs, sml, rr, ei, x.pos);
				}

				if (roleAgnosticFeatures) {
					b(v, refs, reg, "contains", x.word);
					if (includePos)
						b(v, refs, reg, "contains", x.pos);
				}
				b(v, refs, lrg, r, "contains", x.word);
				b(v, refs, lrg, rr, "contains", x.word);
				if (includePos) {
					b(v, refs, lrg, r, "contains", x.pos);
					b(v, refs, lrg, rr, "contains", x.pos);
				}
			}
		}

		if (globalParams.getParserParams().useSyntaxFeatures) {
			// How many external parents?
			int externalParents = 0;
			for (int i=argSpan.start; i<argSpan.end; i++)
				if (!argSpan.includes(sent.governor(i)))
					externalParents++;
			if (externalParents > 0) {
				String externalParentsStr = intTrunc(externalParents, 5);
				if (roleAgnosticFeatures)
					b(v, refs, "externalParents", externalParentsStr);
				b(v, refs, r, "externalParents", externalParentsStr);
				b(v, refs, rr, "externalParents", externalParentsStr);
			}

			// Headword has external parent?
			if (argSpan.includes(sent.governor(argHeadIdx))) {
				if (roleAgnosticFeatures)
					b(v, refs, "no-head-external-parent");
				b(v, refs, r, "no-head-external-parent");
				b(v, refs, rr, "no-head-external-parent");
			}

			// Num children who are external to this span?
			int externalChildren = 0;
			for (int i=argSpan.start; i<argSpan.end; i++)
				for (int j : sent.childrenOf(i))
					if (!argSpan.includes(j))
						externalChildren++;
			if (externalChildren > 0) {
				String externalChildrensStr = intTrunc(externalChildren, 5);
				if (roleAgnosticFeatures)
					b(v, refs, "externalChildren", externalChildrensStr);
				b(v, refs, r, "externalChildren", externalChildrensStr);
				b(v, refs, rr, "externalChildren", externalChildrensStr);
			}

			// Headword's external children
			int headExternalChildren = 0;
			for (int i : sent.childrenOf(argHeadIdx))
				if (!argSpan.includes(i))
					headExternalChildren++;
			if (headExternalChildren > 0) {
				String headExternalChildrenStr =
						intTrunc(headExternalChildren, 5);
				if (roleAgnosticFeatures)
					b(v, refs, "headExternalChildren", headExternalChildrenStr);
				b(v, refs, r, "headExternalChildren", headExternalChildrenStr);
				b(v, refs, rr, "headExternalChildren", headExternalChildrenStr);
			}
		}
	}
}
