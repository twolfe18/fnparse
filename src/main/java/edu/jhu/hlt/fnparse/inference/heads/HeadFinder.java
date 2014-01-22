package edu.jhu.hlt.fnparse.inference.heads;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public interface HeadFinder {
	
	public int head(Span s, Sentence sent);
	
}
