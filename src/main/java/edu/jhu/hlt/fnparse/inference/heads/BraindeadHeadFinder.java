package edu.jhu.hlt.fnparse.inference.heads;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class BraindeadHeadFinder implements HeadFinder {

	private static final long serialVersionUID = 1L;

	@Override
	public int head(Span s, Sentence sent) {
		if(s == Span.nullSpan)
			throw new IllegalArgumentException();
		return s.end - 1;
	}

}
