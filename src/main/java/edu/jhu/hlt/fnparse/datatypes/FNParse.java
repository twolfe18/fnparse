package edu.jhu.hlt.fnparse.datatypes;

import java.util.List;

/**
 * a Sentence with Frame's identified with their arguments.
 * @author travis
 */
public class FNParse extends FNTagging {

	public FNParse(Sentence s, List<FrameInstance> fis) {
		super(s, fis);
		for(FrameInstance fi : fis)
			if(fi.onlyTargetLabeled())
				throw new IllegalArgumentException();
	}

}
