package edu.jhu.hlt.fnparse.indexing;

import edu.jhu.gm.feat.FeatureVector;

/**
 * Joe computes features.
 * @author travis
 */
public interface Joe<T> {

	public void storeJoeInfo(T info);
	
	public T getJoeInfo();
	
	public String getJoeName();
	
	
	
	
	
	
	
	
	
	static class JoeExample<R> implements Joe<R> {

		private R stuff;
		private Bob<R> bob;
		
		@SuppressWarnings("unchecked")
		public JoeExample() {
			bob = (Bob<R>) SuperBob.getBob(this);	// SuperBob calls bob.register for you
		}
		
		@Override
		public void storeJoeInfo(R stuff) { this.stuff = stuff; }

		@Override
		public R getJoeInfo() { return stuff; }
	
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
