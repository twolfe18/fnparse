package edu.jhu.hlt.fnparse.util;

import edu.jhu.util.Alphabet;

public interface HasFeatureAlphabet {
	
	/**
	 * DO NOT CACHE this value!
	 * the alphabet may change, and we want to ensure that if it does,
	 * you didn't store a direct reference to the old alphabet.
	 * 
	 * Never store Alphabets, store instances of HasFeatureAlphabet.
	 * 
	 * Further, any time you store an instance of this class,
	 * it should be final so that you know you will never run into
	 * this staleness issue.
	 */
	public Alphabet<String> getAlphabet();
}
