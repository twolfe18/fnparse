package edu.jhu.hlt.fnparse.inference;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface FrameNetParser {

	public String getName();
	
	public void parse(List<Sentence> sentences);
	
}
