package edu.jhu.hlt.fnparse.datatypes;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * a Sentence with Frame's identified with their arguments.
 * 
 * @author travis
 */
public class FNParse extends FNTagging {
	
	/**
	 * @param s is the Sentence that has been parsed
	 * @param frameInstances are the frames that appear in the Sentence (must have arguments)
	 * @param isFullParse is true if an attempt has been made to annotate all Frames that might
	 *        appear in this sentence. A case where this will be false is for lexical examples
	 *        from Framenet (where only one Frame is annotated).
	 */
	public FNParse(Sentence s, List<FrameInstance> frameInstances) {
		super(s, frameInstances);
		Set<Span> seenTargets = new HashSet<Span>();
		for(FrameInstance fi : frameInstances) {
			if(fi.onlyTargetLabeled())
				throw new IllegalArgumentException();
			if(!seenTargets.add(fi.getTarget())) {
				throw new IllegalArgumentException(
						"you can't have two FrameInstances with the same target!: " + s.getId());
			}
		}
	}
	
}
