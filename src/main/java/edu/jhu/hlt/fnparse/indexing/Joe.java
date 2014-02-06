package edu.jhu.hlt.fnparse.indexing;

import edu.jhu.gm.feat.FeatureVector;

/**
 * Joe computes features.
 * @author travis
 */
public interface Joe {

	/**
	 * Bob is going to assign Joe his own id,
	 * which Joe needs to hold onto for Bob.
	 */
	public void setJoeId(int id);
	
	/**
	 * Bob: hey Joe, what id did I give you?
	 */
	public int getJoeId();
	
	
	
	static class JoeExample implements Joe {

		private int joeId = -1;
		private Bob bob = SuperBob.getBob();
		
		@Override
		public void setJoeId(int id) { joeId = id; }

		@Override
		public int getJoeId() { return joeId; }
	
		public FeatureVector getFeatures(int something) {
			FeatureVector fv = new FeatureVector();
			// add the darn features
			FeatureVector maybeChangedFv = bob.doYourThing(fv, this);
			return maybeChangedFv;
		}
		
	}
}
