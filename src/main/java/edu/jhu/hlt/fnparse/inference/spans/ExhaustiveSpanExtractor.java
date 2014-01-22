package edu.jhu.hlt.fnparse.inference.spans;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class ExhaustiveSpanExtractor implements SpanExtractor {
	
	@Override
	public String getName() { return "AllSpans"; }

	@Override
	public List<Span> computeSpans(Sentence s) {
		int n = s.size();
		List<Span> spans = new ArrayList<Span>();
		spans.add(Span.nullSpan);
		for(int i=0; i<n; i++)
			for(int j=i+1; j<=n; j++)
				spans.add(new Span(i, j));
		return spans;
	}

}
