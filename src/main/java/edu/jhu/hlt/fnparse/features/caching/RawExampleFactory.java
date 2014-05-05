package edu.jhu.hlt.fnparse.features.caching;

import java.util.Iterator;
import java.util.List;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleList;
import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.util.Timer;

/**
 * stores a list of sentences and then instantiates FgExamples
 * on demand (computing features each time).
 * @author travis
 */
public class RawExampleFactory implements FgExampleList {

	//private ParsingSentenceStats psStats = new ParsingSentenceStats();
	private Timer getTimer = new Timer("[RawExampleFactory get]");
	private List<FNParse> baseExamples;	// labels with no inference data (e.g. features)
	private Parser makeExamplesWith;
	
	public RawExampleFactory(List<FNParse> baseExamples, Parser makeExamplesWith) {
		this.baseExamples = baseExamples;
		this.makeExamplesWith = makeExamplesWith;
	}
	
	public void setTimerPrintInterval(int interval) {
		getTimer.printIterval = interval;
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
		
		FNParse p = baseExamples.get(i);

		getTimer.start();
		List<LabeledFgExample> exs = makeExamplesWith.getExampleForTraining(p);
		getTimer.stop();

		assert exs.size() == 1;
		return exs.get(0);
	}

	@Override
	public int size() {
		return baseExamples.size();
	}	
}
