package edu.jhu.hlt.fnparse.inference.misc;

import edu.jhu.gm.model.Var;
import edu.jhu.hlt.fnparse.datatypes.Span;

// TODO implement this (Matt's implementation of CkyFactor may help)
public interface CParseVars {
	Var getConstituentVar(int from, int to);
	Iterable<Span> getAllConstituents();
	Iterable<Var> getAllVariables();
}