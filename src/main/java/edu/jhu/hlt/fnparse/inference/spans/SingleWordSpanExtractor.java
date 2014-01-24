package edu.jhu.hlt.fnparse.inference.spans;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class SingleWordSpanExtractor extends SpanExtractor {
	
	@Override
	public String getName() { return "SingleWordSpans"; }

	/**
	 * does NOT include the null span
	 */
	@Override
	public Integer computeSpansAndLookFor(Sentence s, Span needle, List<Span> addTo) {
		Integer idx = null;
		int n = s.size();
		for(int i=0; i<n; i++) {
			Span sp = Span.widthOne(i);
			addTo.add(sp);
			if(sp.equals(needle)) {
				assert idx == null;
				idx = addTo.size()-1;
			}
		}
		return idx;
	}

}
