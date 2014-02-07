package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.indexing.BasicBob;
import edu.jhu.hlt.fnparse.features.indexing.Joe;
import edu.jhu.hlt.fnparse.features.indexing.JoeInfo;
import edu.jhu.hlt.fnparse.features.indexing.SuperBob;
import edu.jhu.util.Alphabet;

public class BasicFramePrototypeFeatures implements Features.FP, Joe<JoeInfo> {
	
	private Alphabet<String> alph;
	private BasicBob bob;
	
	public BasicFramePrototypeFeatures() {
		this.bob = (BasicBob) SuperBob.getBob(this);
		this.alph = bob.trackMyAlphabet(this);
	}
	
	private int index(String featureName) {
		return alph.lookupIndex(featureName, true);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, int targetHeadIdx, LexicalUnit p, Sentence s) {
		FeatureVector fv = new FeatureVector();
		// frame backoff features are done elsewhere
		fv.add(index("frame=" + f.getId() + "_prototype=" + p.getFullString()), 1d);
		fv.add(index("prototype=" + p.getFullString()), 1d);
		return bob.doYourThing(fv, this);
	}

	private JoeInfo joeInfo;

	@Override
	public String getJoeName() {
		return this.getClass().getName();
	}

	@Override
	public void storeJoeInfo(JoeInfo info) { joeInfo = info; }

	@Override
	public JoeInfo getJoeInfo() { return joeInfo; }


}
