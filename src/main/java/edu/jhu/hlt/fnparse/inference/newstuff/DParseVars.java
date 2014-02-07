package edu.jhu.hlt.fnparse.inference.newstuff;

import edu.jhu.gm.model.Var;

// TODO factor out of Matt's code (SrlFactorGraph)
public interface DParseVars {
	Var getDependencyVar(int gov, int dep);
	Iterable<Var> getAllVariables();
}