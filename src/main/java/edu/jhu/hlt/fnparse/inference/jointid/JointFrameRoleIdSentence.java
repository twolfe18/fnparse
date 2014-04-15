package edu.jhu.hlt.fnparse.inference.jointid;

import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.ParsingSentence;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;

public class JointFrameRoleIdSentence extends ParsingSentence<FrameInstanceHypothesis, FNParse> {
	
	// TODO for pruning experiments, probably want to be able to keep stats of what
	// was kept and what was pruned.
	// TODO am I going to do everything here? e.g. have a FrameIdSentence and a ArgIdSentence
	// for doing the first stage of the cascade for joint inference?

	public JointFrameRoleIdSentence(Sentence s, ParserParams params) {
		super(s, params, params.factorsForJointId);
		throw new RuntimeException("implement me");
	}

	public JointFrameRoleIdSentence(Sentence s, ParserParams params, FNParse gold) {
		super(s, params, params.factorsForJointId, gold);
		throw new RuntimeException("implement me");
	}

	@Override
	public FNParse decode(FgModel model, FgInferencerFactory infFactory) {
		throw new RuntimeException("implement me");
	}

}
