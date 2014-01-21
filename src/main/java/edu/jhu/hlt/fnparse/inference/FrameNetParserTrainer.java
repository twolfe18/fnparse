package edu.jhu.hlt.fnparse.inference;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface FrameNetParserTrainer {

	public String getName();
	
	public FrameNetParser train(List<Sentence> examples);
	
}
