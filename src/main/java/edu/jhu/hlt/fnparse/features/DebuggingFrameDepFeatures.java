package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.util.Alphabet;

public class DebuggingFrameDepFeatures extends AbstractFeatures<DebuggingFrameDepFeatures> implements Features.FD {

	private static final long serialVersionUID = 1L;

	public DebuggingFrameDepFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}

	@Override
	public void featurize(FeatureVector v, Refinements r, int i, Frame f, int lIdx, Sentence s) {

		double sml = 0.2d;
		double med = 0.5d;
		double reg = 1d;
		double big = 2d;
		
		LexicalUnit t = s.getLemmaLU(i);
		LexicalUnit l = s.getLemmaLU(lIdx);
		String lDir = "lDir=" + (lIdx < i ? "left" : "right");
		String lDist = "lDist=" + Math.min(Math.abs(i - lIdx) / 5, 3) + "-" + lDir;

		b(v, r, reg, t.word, l.word);
		b(v, r, reg, t.pos, l.word);
		b(v, r, reg, t.word, l.pos);
		b(v, r, big, t.pos, l.pos);

		b(v, r, med, lDir, t.word, l.word);
		b(v, r, reg, lDir, t.pos, l.word);
		b(v, r, reg, lDir, t.word, l.pos);
		b(v, r, reg, lDir, t.pos, l.pos);

		b(v, r, sml, lDist, t.word, l.word);
		b(v, r, med, lDist, t.pos, l.word);
		b(v, r, med, lDist, t.word, l.pos);
		b(v, r, med, lDist, t.pos, l.pos);
	}

}
