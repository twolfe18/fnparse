package edu.jhu.hlt.fnparse.data;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface FrameInstanceProvider {
	
	public String getName();
	
	/**
	 * @deprecated the return type of this is changing to FNParse because
	 * FrameInstances will no longer live in Sentence.
	 */
	public List<Sentence> getFrameInstances();
	
	public List<FNParse> getParsedSentences();
}
