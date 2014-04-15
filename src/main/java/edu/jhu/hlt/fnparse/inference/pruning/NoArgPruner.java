package edu.jhu.hlt.fnparse.inference.pruning;

import java.io.Serializable;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public class NoArgPruner implements IArgPruner, Serializable {

	@Override
	public boolean pruneArgHead(Frame f, int roleIdx, int headWordIdx, Sentence sentence) {
		return false;
	}

}
