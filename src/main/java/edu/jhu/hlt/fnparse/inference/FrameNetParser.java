package edu.jhu.hlt.fnparse.inference;

import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface FrameNetParser {

	public String getName();
	
	public Map<Sentence, List<FrameInstance>> parse(List<Sentence> sentences);
	
}
