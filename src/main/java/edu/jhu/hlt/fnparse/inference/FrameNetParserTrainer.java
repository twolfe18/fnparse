package edu.jhu.hlt.fnparse.inference;

import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface FrameNetParserTrainer {

	public String getName();
	
	public FrameNetParser train(Map<Sentence, List<FrameInstance>> examples);
	
}
