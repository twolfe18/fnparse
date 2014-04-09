package edu.jhu.hlt.fnparse.inference.sentence;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.newstuff.FactorFactory;
import edu.jhu.hlt.fnparse.inference.newstuff.FrameInstanceHypothesis;
import edu.jhu.hlt.fnparse.inference.newstuff.FrameVars;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;

/**
 * does just frame id (pipeline version -- no role vars ever instantiated).
 * can work with or without latent syntax.
 * 
 * @author travis
 */
public class FrameIdSentence extends ParsingSentence {
	
	private FNTagging gold;

	public FrameIdSentence(Sentence s, ParserParams params) {
		super(s, params);
	}

	public FrameIdSentence(FNTagging gold, ParserParams params) {
		super(gold.getSentence(), params);
		this.gold = gold;
	}
	
	private void createVars() {
		frameRoleVars.clear();
		int n = sentence.size();
		for(int i=0; i<n; i++) {
			FrameVars fv = makeFrameVar(sentence, i, params.logDomain);
			frameRoleVars.add(new FrameInstanceHypothesis(sentence, fv, params));
		}
	}

	@Override
	public FNTagging decode() {
		throw new RuntimeException("implement me");
	}

	@Override
	public FgExample getTrainingExample() {
		
		createVars();
		
		if(this.gold == null)
			throw new IllegalStateException("use the constructor with a gold FNTagging if you'd like to train");
		super.setGold(gold);
		
		FactorGraph fg = new FactorGraph();
		VarConfig gold = new VarConfig();
		
		// create factors
		List<Factor> factors = new ArrayList<Factor>();
		if(params.useLatentDepenencies) {
			assert depTree != null;
			factors.add(depTree);
		}
		for(FactorFactory ff : factorTemplates)
			factors.addAll(ff.initFactorsFor(sentence, frameRoleVars, depTree));
		
		// register all the variables and factors
		for(FrameInstanceHypothesis fhyp : this.frameRoleVars)
			fhyp.register(fg, gold);

		// add factors to the factor graph
		for(Factor f : factors)
			fg.addFactor(f);

		return new FgExample(fg, gold, this);
	}

}
