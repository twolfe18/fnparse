package edu.jhu.hlt.fnparse.features.caching;

import java.util.Iterator;
import java.util.List;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleList;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.Mode;
import edu.jhu.hlt.fnparse.inference.sentence.ParsingSentence;
import edu.jhu.hlt.fnparse.util.ParsingSentenceStats;
import edu.jhu.hlt.fnparse.util.Timer;

/**
 * stores a list of sentences and then instantiates FgExamples
 * on demand (computing features each time).
 * @author travis
 */
public class RawExampleFactory implements FgExampleList {

	private ParsingSentenceStats psStats = new ParsingSentenceStats();
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
		
		getTimer.start();
		
		ParsingSentence.FgExample use;
		if(makeExamplesWith.params.mode == Mode.PIPELINE_FRAME_ARG) {
			
			// TODO this is inefficient!
			// i call getExampleForTraining twice when I should be calling it once.
			
			FNParse p = baseExamples.get(i / 2);
			List<ParsingSentence.FgExample> exs = makeExamplesWith.getExampleForTraining(p);
			assert exs.size() == 2;
			use = exs.get(i % 2);
		}
		else {
			FNParse p = baseExamples.get(i);
			List<ParsingSentence.FgExample> exs = makeExamplesWith.getExampleForTraining(p);
			assert exs.size() == 1;
			use = exs.get(0);
		}
		getTimer.stop();
		
		if(psStats != null) {
			psStats.accum(use.cameFrom);
			if(i % 250 == 0)
				psStats.printStats(System.out);
		}
		
		return use;
	}

	@Override
	public int size() {
		int n = baseExamples.size();
		if(makeExamplesWith.params.mode == Mode.PIPELINE_FRAME_ARG)
			return n * 2;
		return n;
	}	
}
