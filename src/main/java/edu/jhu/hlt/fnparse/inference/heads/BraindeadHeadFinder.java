package edu.jhu.hlt.fnparse.inference.heads;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class BraindeadHeadFinder implements HeadFinder {

	@Override
	public int head(Span s, Sentence sent) {
		return s.end - 1;
	}

}
