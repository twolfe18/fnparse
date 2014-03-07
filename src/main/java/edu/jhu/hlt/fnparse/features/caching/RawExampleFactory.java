package edu.jhu.hlt.fnparse.features.caching;

import java.util.*;

import edu.jhu.gm.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.inference.newstuff.*;

/**
 * stores a list of sentences and then instantiates FgExamples
 * on demand (computing features each time).
 * @author travis
 */
public class RawExampleFactory implements FgExampleList {

	final private List<FNParse> baseExamples;	// labels with no inference data (e.g. features)
	final private Parser makeExamplesWith;
	
	public RawExampleFactory(List<FNParse> baseExamples, Parser makeExamplesWith) {
		this.baseExamples = baseExamples;
		this.makeExamplesWith = makeExamplesWith;
	}
	
	@Override
	public Iterator<FgExample> iterator() {
		return new Iterator<FgExample>() {
			int i = 0;
			int n = baseExamples.size();
			@Override
			public boolean hasNext() { return i < n; }
			@Override
			public FgExample next() { return get(i++); }
			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
	}

	@Override
	public FgExample get(int i) {
		System.out.println("[RawExampleFactory] get " + i);
		FNParse p = baseExamples.get(i);
		return makeExamplesWith.getExampleForTraining(p, true);
	}

	@Override
	public int size() {
		return baseExamples.size();
	}	
}
