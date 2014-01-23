package edu.jhu.hlt.fnparse.inference.spans;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public abstract class SpanExtractor {

	// TODO have option to return features on each span?
	
	public abstract String getName();
	
	/**
	 * what the spans mean is implementation specific,
	 * could be target spans or argument spans.
	 */
	public List<Span> computeSpans(Sentence s) {
		List<Span> spans = new ArrayList<Span>();
		computeSpansAndLookFor(s, null, spans);
		return spans;
	}
	
	/**
	 * Compute spans and add them to addTo.
	 * If needle is found while computing spans,
	 * return its index, otherwise return null.
	 */
	public abstract Integer computeSpansAndLookFor(Sentence s, Span needle, List<Span> addTo);
}
