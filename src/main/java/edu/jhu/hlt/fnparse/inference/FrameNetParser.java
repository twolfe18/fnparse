package edu.jhu.hlt.fnparse.inference;

import java.util.List;

import edu.jhu.hlt.fnparse.util.FrameInstance;
import edu.jhu.hlt.fnparse.util.Sentence;


public interface FrameNetParser {

	public List<FrameInstance> parse(Sentence s);
	
	public void train(List<FrameInstance> examples);
	
}
