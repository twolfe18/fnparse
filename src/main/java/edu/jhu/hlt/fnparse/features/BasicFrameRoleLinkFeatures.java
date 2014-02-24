package edu.jhu.hlt.fnparse.features;

import java.util.Arrays;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Features.FRL;
import edu.jhu.util.Alphabet;

// this factor represents the cube (f_i, r_ijk, l_ij)
// to prevent over-parameterization, we need to fix one of these entries
// lets have features(f_i=nullFrame, r_ijk=false, l_ij=false) = emptyVector
// everything else will get features.

// i'll go one step further: i don't really care about values of this factor
// once i know f_i == nullFrame. have one feature for that, don't regualarize it,
// and then go all out on features for the other combinations.

public class BasicFrameRoleLinkFeatures extends AbstractFeatures<BasicFrameRoleLinkFeatures> implements FRL {

	private FeatureVector nullFrameFeatures;
	private int nullFrameFeatureIdx;
	
	public BasicFrameRoleLinkFeatures(Alphabet<String> featIdx) {
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
	public FeatureVector getFeatures(Frame f, boolean argIsRealized, boolean linkFromTargetHeadToArgHead,
			int targetHeadIdx, int roleIdx, int argHeadIdx, Sentence s) {
		
		if(f == Frame.nullFrame)
			return nullFrameFeatures;

		FeatureVector fv = new FeatureVector();
		
		String fs = "f" + f.getId();
		String fsb = f == Frame.nullFrame ? "fNull" : "fX";
		String as = "-argRealized=" + argIsRealized;
		String ak = "-k" + roleIdx;
		String ls = "-link=" + linkFromTargetHeadToArgHead;
		String dir = "-dir=" + (targetHeadIdx < argHeadIdx ? "right" : "left");
		String mag1 = "-m1=" + (Math.abs(targetHeadIdx - argHeadIdx));
		String mag2 = "-m2=" + (Math.abs(targetHeadIdx - argHeadIdx)/2);
		String mag3 = "-m3=" + (Math.abs(targetHeadIdx - argHeadIdx)/4);
		
		// full
		b(fv, fs + as + ak + ls + dir + mag1);
		b(fv, fs + as + ak + ls + dir + mag2);
		b(fv, fs + as + ak + ls + dir + mag3);
		b(fv, fs + as + ak + ls + dir);
		b(fv, fs + as + ak + ls);
		
		// frame backoff
		b(fv, fsb + as + ls + dir + mag1);
		b(fv, fsb + as + ls + dir + mag2);
		b(fv, fsb + as + ls + dir + mag3);
		b(fv, fsb + as + ls + dir);
		b(fv, fsb + as + ls);
		
		// role backoff
		b(fv, fs + as + ls + dir + mag1);
		b(fv, fs + as + ls + dir + mag2);
		b(fv, fs + as + ls + dir + mag3);
		b(fv, fs + as + ls + dir);
		b(fv, fs + as + ls);
		
		// TODO add more specific features (e.g. distance, lexicalizations, etc)
		
		return fv;
	}

}
