package edu.jhu.hlt.fnparse.inference.frameid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.misc.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.misc.ParsingSentence;
import edu.jhu.hlt.fnparse.inference.misc.Parser.ParserParams;
import edu.jhu.prim.arrays.Multinomials;

/**
 * does just frame id (pipeline version -- no role vars ever instantiated).
 * can work with or without latent syntax.
 * 
 * @author travis
 */
public class FrameIdSentence extends ParsingSentence<FrameVars, FNTagging> {

	public FrameIdSentence(Sentence s, ParserParams params) {
		super(s, params, params.factorsForFrameId);
	}

	public FrameIdSentence(Sentence s, ParserParams params, FNTagging gold) {
		super(s, params, params.factorsForFrameId, gold);
	}
	

	@Override
	public FNParse decode(FgModel model, FgInferencerFactory infFactory) {

		FgExample fg = getExample(false);
		fg.updateFgLatPred(model, params.logDomain);

		FgInferencer inf = infFactory.getInferencer(fg.getFgLatPred());
		inf.run();

		List<FrameInstance> fis = new ArrayList<FrameInstance>();
		for(FrameVars fvars : this.hypotheses) {
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
	protected void setGold(FNTagging p) {
			
		if(p.getSentence() != sentence)
			throw new IllegalArgumentException();
		
		// build an index from targetHeadIdx to FrameRoleVars
		Set<FrameVars> haventSet = new HashSet<FrameVars>();
		FrameVars[] byHead = new FrameVars[sentence.size()];
		for(FrameVars fHyp : this.hypotheses) {
			byHead[fHyp.getTargetHeadIdx()] = fHyp;
			haventSet.add(fHyp);
		}
		
		// match up each FI to a FIHypothesis by the head word in the target
		for(FrameInstance fi : p.getFrameInstances()) {
			Span target = fi.getTarget();
			int head = params.headFinder.head(target, sentence);
			FrameVars fHyp = byHead[head];
			if(fHyp == null) continue;	// nothing you can do here
			fHyp.setGold(fi);
			boolean removed = haventSet.remove(fHyp);
			assert removed : "two FrameInstances with same head?";
		}
		
		// the remaining hypotheses must be null because they didn't correspond to a FI in the parse
		for(FrameVars fHyp : haventSet)
			fHyp.setGoldIsNull();
	}

}
