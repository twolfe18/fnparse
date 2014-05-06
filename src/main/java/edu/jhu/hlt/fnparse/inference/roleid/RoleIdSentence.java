package edu.jhu.hlt.fnparse.inference.roleid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.Var;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.ParsingSentence;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars.RVar;

/**
 * given some frames (grounded with targets), find out where there args are.
 * can do latent syntax or not.
 * 
 * @author travis
 */
public class RoleIdSentence extends ParsingSentence<RoleVars, FNParse> {
	
	// List<RoleVars> hypothesis, each one stores a frame
	public boolean debugDecode = true;

	public RoleIdSentence(Sentence s, FNTagging frames, ParserParams params) {
		super(s, params, params.factorsForRoleId);
		initHypotheses(frames, null, false);
	}

	public RoleIdSentence(Sentence s, FNTagging frames, ParserParams params, FNParse gold) {
		super(s, params, params.factorsForRoleId, gold);
		initHypotheses(frames, gold, true);
	}
	
	/**
	 * Creates the needed variables and puts them in super.hypotheses.
	 * 
	 * @param frames
	 * @param gold can be null if !hasGold
	 * @param hasGold
	 */
	private void initHypotheses(FNTagging frames, FNParse gold, boolean hasGold) {

		if(hasGold && gold.getSentence() != frames.getSentence())
			throw new IllegalArgumentException();
		
		// build an index keying off of the target head index
		FrameInstance[] fiByTarget = null;
		if(hasGold)
			fiByTarget = getFrameInstancesIndexByHeadword(gold.getFrameInstances(), sentence, params.headFinder);

		hypotheses = new ArrayList<RoleVars>();
		for(FrameInstance fi : frames.getFrameInstances()) {
			Span target = fi.getTarget();
			int targetHead = params.headFinder.head(target, fi.getSentence());
			
			RoleVars rv;
			if(hasGold) {	// train mode
				FrameInstance goldFI = fiByTarget[targetHead];
				rv = new RoleVars(goldFI, targetHead, fi.getFrame(), fi.getSentence(), params);
			}
			else	// predict/decode mode
				rv = new RoleVars(targetHead, fi.getFrame(), fi.getSentence(), params);

			hypotheses.add(rv);
		}
	}
	
	private static void fill2d(double[][] mat, double val) {
		for(int i=0; i<mat.length; i++)
			Arrays.fill(mat[i], val);
	}

	@Override
	public FNParse decode(FgModel model, FgInferencerFactory infFactory) {
		
		FgExample fg = getExample(false);
		fg.updateFgLatPred(model, params.logDomain);
		
		FactorGraph fgLatPred = fg.getFgLatPred();
		FgInferencer inf = infFactory.getInferencer(fgLatPred);
		inf.run();
		
		List<FrameInstance> fis = new ArrayList<FrameInstance>();
		for(RoleVars rv : hypotheses)
			fis.add(decodeRoleVars(rv, inf, sentence, params));
		
		return new FNParse(sentence, fis);
	}
	
	public static FrameInstance decodeRoleVars(RoleVars rv, FgInferencer inf, Sentence sentence, ParserParams params) {

		// max over j for every role
		final int n = sentence.size();
		final int K = rv.getFrame().numRoles();
		Span[] arguments = new Span[K];
		Arrays.fill(arguments, Span.nullSpan);
		double[][] beliefs = new double[K][n+1];	// last inner index is "not realized"
		if(params.logDomain) fill2d(beliefs, Double.NEGATIVE_INFINITY);	// otherwise default is 0

		Iterator<RVar> iter = rv.getVars();
		while(iter.hasNext()) {
			RVar rvar = iter.next();
			DenseFactor df = inf.getMarginals(rvar.roleVar);
			beliefs[rvar.k][rvar.j] = df.getValue(BinaryVarUtil.boolToConfig(true));
		}
		for(int k=0; k<K; k++) {

			// TODO add Exactly1 factor!
			params.normalize(beliefs[k]);

			int jHat = params.argDecoder.decode(beliefs[k], n);
			if(jHat < n) {
				Var expansionVar = rv.getExpansionVar(jHat, k);
				DenseFactor df = inf.getMarginals(expansionVar);
				arguments[k] = rv.getArgSpanFor(df.getArgmaxConfigId(), jHat, k);
			}
		}

		return FrameInstance.newFrameInstance(rv.getFrame(), Span.widthOne(rv.getTargetHead()), arguments, sentence);
	}

}
