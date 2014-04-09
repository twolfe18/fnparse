package edu.jhu.hlt.fnparse.inference.sentence;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.data.UnlabeledFgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.newstuff.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.newstuff.FactorFactory;
import edu.jhu.hlt.fnparse.inference.newstuff.FrameInstanceHypothesis;
import edu.jhu.hlt.fnparse.inference.newstuff.FrameVars;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;
import edu.jhu.prim.arrays.Multinomials;

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
	
	private FgExample setup(boolean setGold) {
		
		// create variables
		frameRoleVars.clear();
		int n = sentence.size();
		for(int i=0; i<n; i++) {
			FrameVars fv = makeFrameVar(sentence, i, params.logDomain);
			if(fv != null)
				frameRoleVars.add(new FrameInstanceHypothesis(sentence, fv, params));
		}
		
		if(setGold) {
			if(this.gold == null)
				throw new IllegalStateException("use the constructor with a gold FNTagging if you'd like to train");
			super.setGold(gold);
		}
		
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

		return setGold
			? new LabeledFgExample(fg, gold)
			: new UnlabeledFgExample(fg, gold);
	}

	@Override
	public FNParse decode(FgModel model, FgInferencerFactory infFactory) {

		FgExample fg = setup(false);
		fg.updateFgLatPred(model, params.logDomain);

		FgInferencer inf = infFactory.getInferencer(fg.getFgLatPred());
		inf.run();

		List<FrameInstance> fis = new ArrayList<FrameInstance>();
		for(FrameInstanceHypothesis fhyp : this.frameRoleVars) {
			FrameVars fvars = fhyp.getFrameVars();
			final int T = fvars.numFrames();
			double[] beliefs = new double[T];
			for(int t=0; t<T; t++) {
				DenseFactor df = inf.getMarginals(fvars.getVariable(t));
				beliefs[t] = df.getValue(BinaryVarUtil.boolToConfig(true));
			}

			if(params.logDomain)
				Multinomials.normalizeLogProps(beliefs);	
			else Multinomials.normalizeProps(beliefs);

			final int nullFrameIdx = fvars.getNullFrameIdx();
			int tHat = params.frameDecoder.decode(beliefs, nullFrameIdx);
			Frame fHat = fvars.getFrame(tHat);
			if(fHat != Frame.nullFrame)
				fis.add(FrameInstance.frameMention(fHat, Span.widthOne(fvars.getTargetHeadIdx()), sentence));
		}
		return new FNParse(sentence, fis);
	}

	@Override
	public LabeledFgExample getTrainingExample() {
		return (LabeledFgExample) setup(true);
	}

}
