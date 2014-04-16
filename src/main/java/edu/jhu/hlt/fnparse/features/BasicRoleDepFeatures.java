package edu.jhu.hlt.fnparse.features;

import java.util.Arrays;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Features.RD;
import edu.jhu.util.Alphabet;

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

	private FeatureVector nullFrameFeatures;
	private int nullFrameFeatureIdx;
	
	public BasicRoleDepFeatures(Alphabet<String> featIdx) {
		super(featIdx);
		nullFrameFeatureIdx = featIdx.lookupIndex(getName() + "_nullFeature", true);
		nullFrameFeatures = new FeatureVector();
		nullFrameFeatures.add(nullFrameFeatureIdx, 1d);
	}
	
	@Override
	public List<Integer> dontRegularize() {
		return Arrays.asList(nullFrameFeatureIdx);
	}
	
	@Override
	public void featurize(FeatureVector fv, Refinements r, int targetHeadIdx, Frame f, int argHeadIdx, int roleIdx, int l, Sentence s) {

		if(r != Refinements.noRefinements)
			throw new RuntimeException("implement me (in AbstractFeatures.b)!");		
		
		// these features will only be used *in addition* to BasicRoleFeatures,
		// so they don't need to be that complicated and get all of those features,
		// just the features that become pertinent once you can look at a dep/gov of an arg head.
		
		double sml = 0.2d;
		double med = 0.5d;
		double reg = 1d;
		double big = 2d;
		
		String rs = f.getName() + "." + f.getRole(roleIdx);
		LexicalUnit a = AbstractFeatures.getLUSafe(argHeadIdx, s);
		LexicalUnit m = AbstractFeatures.getLUSafe(l, s);
		String amDir = l == targetHeadIdx ? "link=target" : (argHeadIdx < l ? "am=left" : "am=right");
		String tmDir = targetHeadIdx < l ? (targetHeadIdx == l ? "tm=same" : "tm=left") : "tm=right";
		String amDist = "amDist=" + Math.min(Math.abs(argHeadIdx - l) / 5, 3) + "-" + amDir;
		String tmDist = "tmDist=" + Math.min(Math.abs(targetHeadIdx - l) / 5, 3) + "-" + tmDir;
		

		b(fv, reg, rs, a.word, m.word);
		b(fv, reg, rs, a.pos, m.word);
		b(fv, reg, rs, a.word, m.pos);
		b(fv, big, rs, a.pos, m.pos);
		

		b(fv, med, rs, amDir, a.word, m.word);
		b(fv, reg, rs, amDir, a.pos, m.word);
		b(fv, reg, rs, amDir, a.word, m.pos);
		b(fv, reg, rs, amDir, a.pos, m.pos);
		
		b(fv, sml, rs, amDist, a.word, m.word);
		b(fv, med, rs, amDist, a.pos, m.word);
		b(fv, med, rs, amDist, a.word, m.pos);
		b(fv, med, rs, amDist, a.pos, m.pos);


		b(fv, med, rs, tmDir, a.word, m.word);
		b(fv, reg, rs, tmDir, a.pos, m.word);
		b(fv, reg, rs, tmDir, a.word, m.pos);
		b(fv, reg, rs, tmDir, a.pos, m.pos);
		
		b(fv, sml, rs, tmDist, a.word, m.word);
		b(fv, med, rs, tmDist, a.pos, m.word);
		b(fv, med, rs, tmDist, a.word, m.pos);
		b(fv, med, rs, tmDist, a.pos, m.pos);
		
		
		b(fv, sml, rs, tmDir, amDir, a.word, m.word);
		b(fv, med, rs, tmDir, amDir, a.pos, m.word);
		b(fv, med, rs, tmDir, amDir, a.word, m.pos);
		b(fv, med, rs, tmDir, amDir, a.pos, m.pos);

		
		/*
		String fs = "f" + f.getId();
		String fsb = f == Frame.nullFrame ? "fNull" : "fX";
		String ak = "-k" + roleIdx;
		String ls = "-link=" + linkFromTargetHeadToArgHead;
		String dir = "-dir=" + (targetHeadIdx < argHeadIdx ? "right" : "left");
		String mag1 = "-m1=" + (Math.abs(targetHeadIdx - argHeadIdx));
		String mag2 = "-m2=" + (Math.abs(targetHeadIdx - argHeadIdx)/2);
		String mag3 = "-m3=" + (Math.abs(targetHeadIdx - argHeadIdx)/4);
		
		// full
		b(fv, fs + ak + ls + dir + mag1);
		b(fv, fs + ak + ls + dir + mag2);
		b(fv, fs + ak + ls + dir + mag3);
		b(fv, fs + ak + ls + dir);
		b(fv, fs + ak + ls);
		
		// frame backoff
		b(fv, fsb + ls + dir + mag1);
		b(fv, fsb + ls + dir + mag2);
		b(fv, fsb + ls + dir + mag3);
		b(fv, fsb + ls + dir);
		b(fv, fsb + ls);
		
		// role backoff
		b(fv, fs + ls + dir + mag1);
		b(fv, fs + ls + dir + mag2);
		b(fv, fs + ls + dir + mag3);
		b(fv, fs + ls + dir);
		b(fv, fs + ls);
		*/
	}

}
