package edu.jhu.hlt.fnparse.features.caching;

import java.util.*;

import edu.jhu.gm.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.inference.newstuff.*;
import edu.jhu.hlt.fnparse.util.ParsingSentenceStats;
import edu.jhu.hlt.fnparse.util.Timer;

/**
 * stores a list of sentences and then instantiates FgExamples
 * on demand (computing features each time).
 * @author travis
 */
public class RawExampleFactory implements FgExampleList {

	private ParsingSentenceStats psStats = new ParsingSentenceStats();
	private Timer getTimer = new Timer("[RawExampleFactory get]", 1);
	private List<FNParse> baseExamples;	// labels with no inference data (e.g. features)
	private Parser makeExamplesWith;
	
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
		getTimer.start();
		FNParse p = baseExamples.get(i);
		ParsingSentence ps = makeExamplesWith.getSentenceForTraining(p);
		FgExample fge = ps.getFgExample();
		getTimer.stop();
		
		if(psStats != null) {
			psStats.accum(ps);
			if(i % 10 == 0)
				psStats.printStats(System.out);
		}
		
		return fge;
	}

	@Override
	public int size() {
		return baseExamples.size();
	}	
}
