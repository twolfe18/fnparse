package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
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
	public FeatureVector getFeatures(Frame f, int targetHeadIdx, FrameInstance p, Sentence s) {
		
		if(!f.equals(p.getFrame()))
			throw new IllegalArgumentException("wut");
		
		FeatureVector fv = new FeatureVector();
		
		// frame backoff features are done elsewhere
		
		String targetHead = s.getWord(targetHeadIdx);
		Span protoTarget = p.getTarget();
		String protoId = f.getId() + "@" + p.getSentence().getId();
		for(int i=protoTarget.start; i<protoTarget.end; i++) {
			if(p.getSentence().getWord(i).equals(targetHead)) {
				fv.add(index("head-in-prototype"), 1d);
				fv.add(index("head-in-prototype_prototype=" + protoId), 1d);
				fv.add(index("head-in-prototype_frame=" + f.getId()), 1d);
			}
		}
		
		int ni = s.size();
		int nj = p.getSentence().size();
		for(int i=0; i<ni; i++) {
			for(int j=0; j<nj; j++) {
				double ip = ((double) i) / ni;
				double ij = ((double) j) / nj;
				double dist = Math.abs(ip - ij);
				if(s.getWord(i).equals(p.getSentence().getWord(j))) {
					fv.add(index("word-match"), 1d - dist);
					int tdist = Math.abs(i - targetHeadIdx);
					boolean l = i < targetHeadIdx;
					fv.add(index("word-match-in" + tdist), 1d - dist);
					fv.add(index("word-match-to" + (l ? "L" : "R")), 1d - dist);
					fv.add(index("word-match-like" + tdist + (l ? "L" : "R")), 1d - dist);
				}
				if(s.getLU(i).equals(p.getSentence().getLU(j))) {
					fv.add(index("word-match-fine"), 1d - dist);
					int tdist = Math.abs(i - targetHeadIdx);
					boolean l = i < targetHeadIdx;
					fv.add(index("word-match-fine-in" + tdist), 1d - dist);
					fv.add(index("word-match-fine-to" + (l ? "L" : "R")), 1d - dist);
					fv.add(index("word-match-fine-like" + tdist + (l ? "L" : "R")), 1d - dist);
				}
			}
		}
		
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
