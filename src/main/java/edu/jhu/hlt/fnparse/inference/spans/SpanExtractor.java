package edu.jhu.hlt.fnparse.inference.spans;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public interface SpanExtractor {

	// TODO have option to return features on each span?
	
	public String getName();
	
	/**
	 * what the spans mean is implementation specific,
	 * could be target spans or argument spans.
	 */
	public List<Span> computeSpans(Sentence s);
	
}
