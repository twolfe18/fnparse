package edu.jhu.hlt.fnparse.inference.spans;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class SingleWordSpanExtractor implements SpanExtractor {
	
	@Override
	public String getName() { return "SingleWordSpans"; }

	@Override
	public List<Span> computeSpans(Sentence s) {
		List<Span> spans = new ArrayList<Span>();
		int n = s.size();
		for(int i=0; i<n; i++)
			spans.add(Span.widthOne(i));
		return spans;
	}

}
