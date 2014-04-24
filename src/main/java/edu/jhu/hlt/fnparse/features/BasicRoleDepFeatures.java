package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Features.RD;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;

// this factor represents the cube (f_i, r_ijk, l_ij)
// to prevent over-parameterization, we need to fix one of these entries
// lets have features(f_i=nullFrame, r_ijk=false, l_ij=false) = emptyVector
// everything else will get features.

// i'll go one step further: i don't really care about values of this factor
// once i know f_i == nullFrame. have one feature for that, don't regualarize it,
// and then go all out on features for the other combinations.

/** this is only relevant to joint parsing, which is now on the back-burner */
public class BasicRoleDepFeatures extends AbstractFeatures<BasicRoleDepFeatures> implements RD {

	private static final long serialVersionUID = 4825729945197288970L;
	
	public BasicRoleDepFeatures(ParserParams params) {
		super(params);
	}
	
	@Override
	public void featurize(FeatureVector fv, Refinements r, int targetHeadIdx, Frame f, int argHeadIdx, int roleIdx, int l, Sentence s) {

		// these features will only be used *in addition* to BasicRoleFeatures,
		// so they don't need to be that complicated and get all of those features,
		// just the features that become pertinent once you can look at a dep/gov of an arg head.
		
		double sml = 0.2d;
		double med = 0.5d;
		double reg = 1d;
		double big = 2d;
		
		String rs = f.getName() + "." + f.getRole(roleIdx);
		LexicalUnit a = s.getLemmaLU(argHeadIdx);
		LexicalUnit m = s.getLemmaLU(l);
		String amDir = l == targetHeadIdx ? "link=target" : (argHeadIdx < l ? "am=left" : "am=right");
		String tmDir = targetHeadIdx < l ? (targetHeadIdx == l ? "tm=same" : "tm=left") : "tm=right";
		String amDist = "amDist=" + Math.min(Math.abs(argHeadIdx - l) / 5, 3) + "-" + amDir;
		String tmDist = "tmDist=" + Math.min(Math.abs(targetHeadIdx - l) / 5, 3) + "-" + tmDir;
		

		b(fv, r, reg, rs, a.word, m.word);
		b(fv, r, reg, rs, a.pos, m.word);
		b(fv, r, reg, rs, a.word, m.pos);
		b(fv, r, big, rs, a.pos, m.pos);
		

		b(fv, r, med, rs, amDir, a.word, m.word);
		b(fv, r, reg, rs, amDir, a.pos, m.word);
		b(fv, r, reg, rs, amDir, a.word, m.pos);
		b(fv, r, reg, rs, amDir, a.pos, m.pos);
		
		b(fv, r, sml, rs, amDist, a.word, m.word);
		b(fv, r, med, rs, amDist, a.pos, m.word);
		b(fv, r, med, rs, amDist, a.word, m.pos);
		b(fv, r, med, rs, amDist, a.pos, m.pos);


		b(fv, r, med, rs, tmDir, a.word, m.word);
		b(fv, r, reg, rs, tmDir, a.pos, m.word);
		b(fv, r, reg, rs, tmDir, a.word, m.pos);
		b(fv, r, reg, rs, tmDir, a.pos, m.pos);
		
		b(fv, r, sml, rs, tmDist, a.word, m.word);
		b(fv, r, med, rs, tmDist, a.pos, m.word);
		b(fv, r, med, rs, tmDist, a.word, m.pos);
		b(fv, r, med, rs, tmDist, a.pos, m.pos);
		
		
		//b(fv, r, sml, rs, tmDir, amDir, a.word, m.word);
		b(fv, r, sml, rs, tmDir, amDir, a.pos, m.word);
		b(fv, r, sml, rs, tmDir, amDir, a.word, m.pos);
		b(fv, r, med, rs, tmDir, amDir, a.pos, m.pos);

	}

}
