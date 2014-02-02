package edu.jhu.hlt.fnparse.data;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface FrameInstanceProvider {
	
	public String getName();
	
	public List<FNParse> getParsedSentences();
}
