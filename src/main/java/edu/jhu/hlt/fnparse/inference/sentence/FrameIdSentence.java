package edu.jhu.hlt.fnparse.inference.sentence;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;

/**
 * does just frame id (pipeline version -- no role vars ever instantiated).
 * can work with or without latent syntax.
 * 
 * @author travis
 */
public class FrameIdSentence extends ParsingSentence {

	public FrameIdSentence(Sentence s, ParserParams params) {
		super(s, params);
	}

	public FrameIdSentence(FNTagging gold, ParserParams params) {
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
