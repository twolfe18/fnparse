package edu.jhu.hlt.fnparse.inference.jointid;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.ParsingSentence;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdSentence;
import edu.jhu.hlt.fnparse.inference.frameid.FrameVars;
import edu.jhu.hlt.fnparse.inference.roleid.RoleIdSentence;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars;

/**
 * I'm making this class both the holder/decoder of hypotheses (extends ParsingSentence)
 * as well as the adder of factors (implements FactorFactory) because the decoding proceedure
 * requires us to query for the beliefs at the joint (f_it ~ r_itjk) factors.
 * 
 * @author travis
 */
public class JointFrameRoleIdSentence extends ParsingSentence<FrameInstanceHypothesis, FNParse>  {
	
	// TODO for pruning experiments, probably want to be able to keep stats of what
	// was kept and what was pruned.
	// TODO am I going to do everything here? e.g. have a FrameIdSentence and a ArgIdSentence
	// for doing the first stage of the cascade for joint inference?

	// NOTE:
	// List<FrameInstanceHypothesis> hypotheses stores the (f_it ~ r_itjk) factors needed for decoding

	/** constructor for decoding/prediction */
	public JointFrameRoleIdSentence(Sentence s, ParserParams params) {
		this(false, s, params, null);
	}

	/** constructor for when you have the gold labels */
	public JointFrameRoleIdSentence(Sentence s, ParserParams params, FNParse gold) {
		this(true, s, params, gold);
	}

	private JointFrameRoleIdSentence(boolean hasGold, Sentence s, ParserParams params, FNParse gold) {
		super(s, params, params.factorsForJointId, gold);
		
		if(hasGold && gold == null)
			throw new IllegalArgumentException();
		
		if(gold != null && gold.getSentence() != s)
			throw new IllegalArgumentException();

		// use FrameIdSentence to construct all of the FrameVars
		FrameIdSentence frameId = hasGold
				? new FrameIdSentence(s, params, gold)
				: new FrameIdSentence(s, params);
		
		this.hypotheses = new ArrayList<FrameInstanceHypothesis>();
		if(hasGold) {
			// build a map of gold FrameInstances by their headword
			FrameInstance[] fiByTarget = new FrameInstance[s.size()];
			for(FrameInstance fi : gold.getFrameInstances()) {
				int t = params.headFinder.head(fi.getTarget(), s);
				assert fiByTarget[t] == null : "two FIs with the same head?";
				fiByTarget[t] = fi;
			}

			for(FrameVars fv : frameId.getPossibleFrames()) {
				FrameInstance goldFI = fiByTarget[fv.getTargetHeadIdx()];
				this.hypotheses.add(new FrameInstanceHypothesis(goldFI, s, fv, params));
			}
		}
		else {
			for(FrameVars fv : frameId.getPossibleFrames())
				this.hypotheses.add(new FrameInstanceHypothesis(s, fv, params));
		}
	}
	

	@Override
	public FNParse decode(FgModel model, FgInferencerFactory infFactory) {
		
		// this will need to iterate over the full (f_it, r_itjk) table to choose the max configuration
		// i think this can be implemented by matt's BP code by asking for the factor beliefs for the
		// f_it ~ r_itjk binary factor

		FgExample fg = getExample(false);
		fg.updateFgLatPred(model, params.logDomain);

		FactorGraph fgLatPred = fg.getFgLatPred();
		FgInferencer inf = infFactory.getInferencer(fgLatPred);
		inf.run();
		
		List<FrameInstance> frames = new ArrayList<FrameInstance>();
		for(FrameInstanceHypothesis fhyp : this.hypotheses) {
			FrameInstance fi = decodeHypothesis(inf, fhyp);
			if(fi != null) frames.add(fi);
		}
		
		return new FNParse(this.sentence, frames);
	}
	
	/**
	 * choose a frame, and its respective roles, according to average factor potential for the MAP solution.
	 * uses RoleIdSentence.decodeRoleVars to handle role selection.
	 * @param inf should have been run already.
	 * @param fhyp max over this.
	 * @return null if nullFrame is decoded
	 */
	private FrameInstance decodeHypothesis(FgInferencer inf, FrameInstanceHypothesis fhyp) {
		
		int tBest = -1;
		double tBestPotential = 0d;
		final int T = fhyp.numFrames();
		for(int t=0; t<T; t++) {
			List<Factor> phis = fhyp.getJointFactors(t);
			double potential = 0d;
			for(Factor phi : phis) {
				// TODO check this code!
				DenseFactor df = inf.getMarginals(phi);
				potential += df.getValue(df.getArgmaxConfigId());
			}
			potential /= phis.size();	// average potential
			
			// TODO there is a problem here,
			// nullFrame doesn't have any roles, and hence no f_it ~ r_itjk factor
			// so i can't get a score for nullFrame
			assert phis.size() > 0;
			
			if(tBest < 0 || potential > tBestPotential) {
				tBest = t;
				tBestPotential = potential;
			}
		}
		
		if(fhyp.getFrame(tBest) == Frame.nullFrame)
			return null;
		else {
			RoleVars rv = fhyp.getRoleVars(tBest);
			return RoleIdSentence.decodeRoleVars(rv, inf, sentence, params);
		}
	}

}
