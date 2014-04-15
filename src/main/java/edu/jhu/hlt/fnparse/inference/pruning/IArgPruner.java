package edu.jhu.hlt.fnparse.inference.pruning;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public interface IArgPruner {

	public boolean pruneArgHead(Frame f, int roleIdx, int headWordIdx, Sentence sentence);

}
