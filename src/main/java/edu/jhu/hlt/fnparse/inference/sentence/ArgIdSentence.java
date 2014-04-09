package edu.jhu.hlt.fnparse.inference.sentence;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.model.FgModel;
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
	public FNParse decode(FgModel model, FgInferencerFactory infFactory) {
		throw new RuntimeException("implement me");
	}

	@Override
	public LabeledFgExample getTrainingExample() {
		throw new RuntimeException("implement me");
	}

}
