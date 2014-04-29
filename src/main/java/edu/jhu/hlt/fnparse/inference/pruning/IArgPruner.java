package edu.jhu.hlt.fnparse.inference.pruning;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface IArgPruner {

	public boolean pruneArgHead(Frame f, int roleIdx, int headWordIdx, Sentence sentence);

	/** indicates that the last call to pruneArgHead was a bad one */
	public void falsePrune();
	public int numFalsePrunes();
	public double pruneRatio();	// can figure this out by return values from pruneArgHead
}
