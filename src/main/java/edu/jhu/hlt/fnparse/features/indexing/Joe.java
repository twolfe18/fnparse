package edu.jhu.hlt.fnparse.features.indexing;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.util.Alphabet;

/**
 * Joe computes features.
 * @author travis
 */
public interface Joe<T> {

	public void storeJoeInfo(T info);
	
	public T getJoeInfo();
	
	public String getJoeName();
	
	
	
	
	
	
	
	
	
	static class JoeExample implements Joe<JoeInfo> {

		private JoeInfo stuff;
		private BasicBob bob;
		private Alphabet<String> featureNames;
		
		public JoeExample() {
			bob = (BasicBob) SuperBob.getBob(this);
			featureNames = bob.trackMyAlphabet(this);
		}
		
		@Override
		public void storeJoeInfo(JoeInfo stuff) { this.stuff = stuff; }

		@Override
		public JoeInfo getJoeInfo() { return stuff; }
	
		public FeatureVector getFeatures(int something) {
			FeatureVector fv = new FeatureVector();
			// add the darn features
			FeatureVector maybeChangedFv = bob.doYourThing(fv, this);
			return maybeChangedFv;
		}

		@Override
		public String getJoeName() {
			return "Joe";
		}
		
	}
}
