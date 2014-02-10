package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.indexing.BasicBob;
import edu.jhu.hlt.fnparse.features.indexing.Joe;
import edu.jhu.hlt.fnparse.features.indexing.JoeInfo;
import edu.jhu.hlt.fnparse.features.indexing.SuperBob;
import edu.jhu.util.Alphabet;

public class ConstituencyFeatures implements Features.C, Joe<JoeInfo> {
	
	private JoeInfo joeInfo;
	private BasicBob bob;
	private String prefix;
	private Alphabet<String> featIdx;

	private String refinement;
	private double refinementW;
	
	public ConstituencyFeatures(String prefix) {
		this.prefix = prefix;
		bob = (BasicBob) SuperBob.getBob(this, BasicBob.NAME);
		featIdx = bob.trackMyAlphabet(this);
	}
	
	public void setRefinement(String s, double w) {
		if(w >= 1d)
			throw new IllegalArgumentException();
		refinement = s;
		refinementW = w;
	}
	
	@Override
	public FeatureVector getFeatures(Span cons, Sentence sent) {
		FeatureVector v = new FeatureVector();

		if(cons == Span.nullSpan)
			return bob.doYourThing(v, this);
		
		b(v, "intercept");
		b(v, "width=" + cons.width());
		b(v, "width/2=" + (cons.width()/2));
		b(v, "width/3=" + (cons.width()/3));
		b(v, "width/4=" + (cons.width()/4));
		
		long p = Math.round((10d * cons.width()) / sent.size());
		b(v, "propWidth=" + p);
		
		int s = cons.start;
		if(s > 0) {
			b(v, "oneLeft=" + sent.getWord(s-1));
			b(v, "oneLeft=" + sent.getPos(s-1));
			if(s > 1) {
				b(v, "twoLeft=" + sent.getWord(s-2));
				b(v, "twoLeft=" + sent.getPos(s-2));
			}
		}
		
		int e = cons.end;
		if(e < sent.size()) {
			b(v, "oneRight=" + sent.getWord(e));
			b(v, "oneRight=" + sent.getPos(e));
			if(e < sent.size() - 1) {
				b(v, "twoRight=" + sent.getWord(e+1));
				b(v, "twoRight=" + sent.getPos(e+1));
			}
		}
		
		for(int i=cons.start; i<cons.end; i++) {
			b(v, "contains_" + sent.getWord(i));
			b(v, "contains_" + sent.getPos(i));
		}
		
		return bob.doYourThing(v, this);
	}
	
	private void b(FeatureVector fv, String featureName) {
		fv.add(i(featureName), 1d);
		if(refinement != null)
			fv.add(i(featureName + "@" + refinement), refinementW);
	}
	
	private int i(String featureName) {
		return featIdx.lookupIndex(featureName, true);
	}

	@Override
	public void storeJoeInfo(JoeInfo info) { joeInfo = info; }

	@Override
	public JoeInfo getJoeInfo() { return joeInfo; }

	/**
	 * by adding this prefix, Bob thinks that each prefix is its own feature
	 * set and keeps their indices apart, allowing for different weights for,
	 * say frame expansions vs argument expansions.
	 */
	@Override
	public String getJoeName() {
		return this.getClass().getName() + "@" + prefix;
	}


}
