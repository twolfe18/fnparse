package edu.jhu.hlt.fnparse.inference.spans;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class ExhaustiveSpanExtractor extends SpanExtractor {
	
	@Override
	public String getName() { return "AllSpans"; }

	/**
	 * includes the null span
	 */
	@Override
	public Integer computeSpansAndLookFor(Sentence s, Span needle, List<Span> addTo) {
		assert addTo.size() == 0;
		addTo.add(Span.nullSpan);
		Integer idx = needle == Span.nullSpan ? 0 : null;
		int n = s.size();
		for(int i=0; i<n; i++) {
			for(int j=i+1; j<=n; j++) {
				Span sp = Span.getSpan(i, j);
				addTo.add(sp);
				if(sp.equals(needle)) {
					assert idx == null;
					idx = addTo.size()-1;
				}
			}
		}
		return idx;
	}
}
