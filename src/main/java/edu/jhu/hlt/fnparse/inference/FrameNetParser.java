package edu.jhu.hlt.fnparse.inference;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface FrameNetParser {

	public List<FrameInstance> parse(Sentence s);
	
	public void train(List<FrameInstance> examples);
	
}
