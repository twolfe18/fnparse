package edu.jhu.hlt.fnparse.inference.pruning;

import java.io.Serializable;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public class NoArgPruner implements IArgPruner, Serializable {

	private static final long serialVersionUID = 1L;

	@Override
	public boolean pruneArgHead(Frame f, int roleIdx, int headWordIdx, Sentence sentence) {
		return false;
	}

	@Override
	public void falsePrune() {}

	@Override
	public int numFalsePrunes() {
		return 0;
	}

	@Override
	public double pruneRatio() {
		return 0;
	}

}
