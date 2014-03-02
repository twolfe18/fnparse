package edu.jhu.hlt.fnparse.data;

import java.util.*;

import edu.jhu.hlt.fnparse.datatypes.*;

public interface FrameInstanceProvider {
	
	public String getName();
	
	/**
	 * return an iterator over FNParses (see that class for definition).
	 */
	public Iterator<FNParse> getParsedSentences();
	
	/**
	 * return an iterator over FNTaggings (see that class for definition).
	 * Do not include proper FNParses in this (i.e. elements in this
	 * iterator should be distinct from elements in the getParsedSentences()
	 * iterator)
	 */
	public Iterator<FNTagging> getTaggedSentences();
	
	/**
	 * union of getParsedSentences() and getTaggedSentences()
	 */
	public Iterator<FNTagging> getParsedAndTaggedSentences();
}
