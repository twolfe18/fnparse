package edu.jhu.hlt.fnparse.inference.sentence;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;

public class JointFrameArgIdSentence extends ParsingSentence {
	
	// TODO for pruning experiments, probably want to be able to keep stats of what
	// was kept and what was pruned.
	// TODO am I going to do everything here? e.g. have a FrameIdSentence and a ArgIdSentence
	// for doing the first stage of the cascade for joint inference?

	public JointFrameArgIdSentence(Sentence s, ParserParams params) {
		super(s, params);
	}

	public JointFrameArgIdSentence(FNTagging gold, ParserParams params) {
		super(gold.getSentence(), params);
		this.setGold(gold);
	}

	@Override
	public FNTagging decode() {
		throw new RuntimeException("implement me");
	}

	@Override
	public FgExample getTrainingExample() {
		throw new RuntimeException("implement me");
	}

}
