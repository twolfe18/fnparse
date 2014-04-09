package edu.jhu.hlt.fnparse.inference.sentence;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;

/**
 * given some frames, find out where there args are.
 * can do latent syntax or not.
 * 
 * @author travis
 */
public class ArgIdSentence extends ParsingSentence {

	/**
	 * @param evoked the frames that appear in this sentence
	 * @param params
	 */
	public ArgIdSentence(FNTagging evoked, ParserParams params) {
		super(evoked.getSentence(), params);
	}

	public ArgIdSentence(FNParse gold, FNTagging evoked, ParserParams params) {
		super(gold.getSentence(), params);
		if(gold.getSentence() != evoked.getSentence())
			throw new IllegalArgumentException();
		this.setGold(gold);
	}

	@Override
	public FNParse decode() {
		throw new RuntimeException("implement me");
	}

	@Override
	public FgExample getTrainingExample() {
		throw new RuntimeException("implement me");
	}

}
