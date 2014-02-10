package edu.jhu.hlt.fnparse.features.indexing;

import edu.jhu.gm.feat.FeatureVector;

public interface Bob<T> {

	/**
	 * Joe should say hello to Bob and tell him that he's going to send him features in the future.
	 */
	public void register(Joe<T> featureComputer);
	
	/**
	 * Bob retains the right to mess with the feature vector passed in.
	 */
	public FeatureVector doYourThing(FeatureVector fv, Joe<T> sender);

	/**
	 * SuperBob will call this before you ever have to doYourThing.
	 * (i.e. load your state here).
	 */
	public void startup();
	
	/**
	 * SuperBob will call this before the process ends.
	 * (i.e. save your state here)
	 */
	public void shutdown();
	
}
