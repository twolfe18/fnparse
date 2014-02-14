package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Features.FRL;
import edu.jhu.hlt.fnparse.features.indexing.Joe;
import edu.jhu.hlt.fnparse.features.indexing.JoeInfo;
import edu.jhu.hlt.fnparse.features.indexing.SuperBob;
import edu.jhu.hlt.fnparse.features.indexing.BasicBob;
import edu.jhu.util.Alphabet;

//this factor represents the cube (f, r, l)
// to prevent over-parameterization, we need to fix one of these entries
// lets have features(f=nullFrame, r=false, l=false) = emptyVector
// everything else will get features

// in the grand scheme of things, over-parameterization isn't that bad,
// so i might just accidentally include all combinations

public class BasicFrameRoleLinkFeatures implements FRL, Joe<JoeInfo> {

	private BasicBob bob;
	private JoeInfo joeInfo;
	private Alphabet<String> featIdx;
	
	public BasicFrameRoleLinkFeatures() {
		bob = (BasicBob) SuperBob.getBob(this);
		featIdx = bob.trackMyAlphabet(this);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, boolean argIsRealized,
			boolean linkFromTargetHeadToArgHead, int targetHeadIdx,
			int roleIdx, int argHeadIdx, Sentence s) {
		
		FeatureVector fv = new FeatureVector();
		
		if(f == Frame.nullFrame && !argIsRealized && !linkFromTargetHeadToArgHead)
			return bob.doYourThing(fv, this);
		
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
		a(fs + as + ak + ls + dir + mag1, fv);
		a(fs + as + ak + ls + dir + mag2, fv);
		a(fs + as + ak + ls + dir + mag3, fv);
		a(fs + as + ak + ls + dir, fv);
		a(fs + as + ak + ls, fv);
		
		// frame backoff
		a(fsb + as + ls + dir + mag1, fv);
		a(fsb + as + ls + dir + mag2, fv);
		a(fsb + as + ls + dir + mag3, fv);
		a(fsb + as + ls + dir, fv);
		a(fsb + as + ls, fv);
		
		// role backoff
		a(fs + as + ls + dir + mag1, fv);
		a(fs + as + ls + dir + mag2, fv);
		a(fs + as + ls + dir + mag3, fv);
		a(fs + as + ls + dir, fv);
		a(fs + as + ls, fv);
		
		// TODO add more specific features (e.g. distance, lexicalizations, etc)
		
		return bob.doYourThing(fv, this);
	}
	
	private void a(String featureName, FeatureVector fv) {
		int i = featIdx.lookupIndex(featureName, true);
		fv.add(i, 1d);
	}
	
	@Override
	public String getJoeName() {
		return this.getClass().getName();
	}

	@Override
	public void storeJoeInfo(JoeInfo info) { joeInfo = info; }

	@Override
	public JoeInfo getJoeInfo() { return joeInfo; }

}
