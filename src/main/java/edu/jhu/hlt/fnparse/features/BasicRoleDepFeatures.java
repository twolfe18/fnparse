package edu.jhu.hlt.fnparse.features;

import java.util.Arrays;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Features.RD;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;


public class BasicRoleDepFeatures extends AbstractFeatures<BasicRoleDepFeatures> implements RD {

	private static final long serialVersionUID = 1L;
	
	public boolean useDist = false;
	public boolean fullConj = false;	// don't do this
	public boolean allowFullLex = false;
	
	public BasicRoleDepFeatures(ParserParams params) {
		super(params);
	}
	
	@Override
	public void featurize(FeatureVector fv, Refinements r, int targetHeadIdx, Frame f, int argHeadIdx, int roleIdx, int l, Sentence s) {

		// these features will only be used *in addition* to BasicRoleFeatures,
		// so they don't need to be that complicated and get all of those features,
		// just the features that become pertinent once you can look at a dep/gov of an arg head.

		LexicalUnit a = s.getLemmaLU(argHeadIdx);
		LexicalUnit m = s.getLemmaLU(l);
		String amDir = (l == argHeadIdx) ? "am=same" : (argHeadIdx < l ? "am=left" : "am=right");
		String tmDir = (l == targetHeadIdx) ? "tm=same" : (targetHeadIdx < l ? "tm=left" : "tm=right");
		String amDist = "amDist=" + intTrunc(Math.abs(argHeadIdx - l)/4, 3) + "-" + amDir;
		String tmDist = "tmDist=" + intTrunc(Math.abs(targetHeadIdx - l)/4, 3) + "-" + tmDir;
		
		double sml = 0.2d;
		double med = 0.5d;
		double reg = 0.7d;
		double big = 1.1d;
		
		for(String rs : Arrays.asList(
				f.getName() + "." + f.getRole(roleIdx),
				f.getRole(roleIdx))) {
			
			b(fv, r, 4d, rs, "intercept");
			b(fv, r, big, rs, amDir);
			b(fv, r, big, rs, tmDir);
			//b(fv, r, big, rs, amDir, tmDir);

			if(allowFullLex)
				b(fv, r, reg, rs, a.word, m.word);
			b(fv, r, reg, rs, a.pos, m.word);
			b(fv, r, reg, rs, a.word, m.pos);
			b(fv, r, big, rs, a.pos, m.pos);

			if(allowFullLex)
				b(fv, r, med, rs, amDir, a.word, m.word);
			b(fv, r, reg, rs, amDir, a.pos, m.word);
			b(fv, r, reg, rs, amDir, a.word, m.pos);
			b(fv, r, reg, rs, amDir, a.pos, m.pos);

			if(useDist) {
				if(allowFullLex)
					b(fv, r, sml, rs, amDist, a.word, m.word);
				b(fv, r, med, rs, amDist, a.pos, m.word);
				b(fv, r, med, rs, amDist, a.word, m.pos);
				b(fv, r, med, rs, amDist, a.pos, m.pos);
			}

			if(allowFullLex)
				b(fv, r, med, rs, tmDir, a.word, m.word);
			b(fv, r, reg, rs, tmDir, a.pos, m.word);
			b(fv, r, reg, rs, tmDir, a.word, m.pos);
			b(fv, r, reg, rs, tmDir, a.pos, m.pos);

			if(useDist) {
				if(allowFullLex)
					b(fv, r, sml, rs, tmDist, a.word, m.word);
				b(fv, r, med, rs, tmDist, a.pos, m.word);
				b(fv, r, med, rs, tmDist, a.word, m.pos);
				b(fv, r, med, rs, tmDist, a.pos, m.pos);
			}

			if(fullConj) {
				//b(fv, r, sml, rs, tmDir, amDir, a.word, m.word);
				b(fv, r, sml, rs, tmDir, amDir, a.pos, m.word);
				b(fv, r, sml, rs, tmDir, amDir, a.word, m.pos);
				b(fv, r, med, rs, tmDir, amDir, a.pos, m.pos);
			}
			
			sml *= 1.5d;
			med *= 1.5d;
			reg *= 1.5d;
			big *= 1.5d;
		}
	}

}
