package edu.jhu.hlt.fnparse.datatypes;

import java.util.List;

/**
 * a Sentence with Frame's identified with their arguments.
 * 
 * @author travis
 */
public class FNParse extends FNTagging {

	private boolean isFullParse;
	
	/**
	 * @param s is the Sentence that has been parsed
	 * @param frameInstances are the frames that appear in the Sentence (must have arguments)
	 * @param isFullParse is true if an attempt has been made to annotate all Frames that might
	 *        appear in this sentence. A case where this will be false is for lexical examples
	 *        from Framenet (where only one Frame is annotated).
	 */
	public FNParse(Sentence s, List<FrameInstance> frameInstances, boolean isFullParse) {
		super(s, frameInstances);
		this.isFullParse = isFullParse;
		for(FrameInstance fi : frameInstances)
			if(fi.onlyTargetLabeled())
				throw new IllegalArgumentException();
	}

	public boolean isFullParse() {
		return isFullParse;
	}
	
}
