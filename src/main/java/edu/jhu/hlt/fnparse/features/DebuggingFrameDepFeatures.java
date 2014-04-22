package edu.jhu.hlt.fnparse.features;

import java.util.Arrays;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;

public class DebuggingFrameDepFeatures extends AbstractFeatures<DebuggingFrameDepFeatures> implements Features.FD {

	private static final long serialVersionUID = 1L;

	public DebuggingFrameDepFeatures(ParserParams params) {
		super(params);
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

		for(String fs : Arrays.asList(f.getName(), f == Frame.nullFrame ? "NullFrame" : "NonNullFrame")) {
			b(v, r, reg, fs, t.word, l.word);
			b(v, r, reg, fs, t.pos, l.word);
			b(v, r, reg, fs, t.word, l.pos);
			b(v, r, big, fs, t.pos, l.pos);

			b(v, r, med, fs, lDir, t.word, l.word);
			b(v, r, reg, fs, lDir, t.pos, l.word);
			b(v, r, reg, fs, lDir, t.word, l.pos);
			b(v, r, reg, fs, lDir, t.pos, l.pos);

			b(v, r, sml, fs, lDist, t.word, l.word);
			b(v, r, med, fs, lDist, t.pos, l.word);
			b(v, r, med, fs, lDist, t.word, l.pos);
			b(v, r, med, fs, lDist, t.pos, l.pos);
		}
	}

}
