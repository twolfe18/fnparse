package edu.jhu.hlt.fnparse.inference.roleid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.ParsingSentence;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars.RVar;
import edu.jhu.hlt.fnparse.util.MultiTimer;

/**
 * given some frames (grounded with targets), find out where there args are.
 * can do latent syntax or not.
 * 
 * @author travis
 */
public class RoleIdSentence extends ParsingSentence<RoleVars, FNParse> {
	
	public static final MultiTimer timer = new MultiTimer();
	
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
	 * In the FN data, there are some parses which have two different FrameInstances
	 * with the same target. Every instance of this I've seen has just been a mistake
	 * (the same Frame, just double tagged). My code is really pedantic and throws an
	 * exception if I produce a parse that has two FrameInstances with the same target,
	 * and this will happen if I use gold frameId through no fault of my own code.
	 * This method selects a FNTagging that doesn't violate this constraint.
	 */
	public static FNTagging filterOutTargetCollisions(FNTagging input) {
		Map<Span, FrameInstance> keep = new HashMap<Span, FrameInstance>();
		boolean someViolation = false;
		for(FrameInstance fi : input.getFrameInstances()) {
			FrameInstance collision = keep.put(fi.getTarget(), fi);
			if(collision != null) {
				someViolation = true;
				// choose the FI with more realized arguments
				if(collision.numRealizedArguments() > fi.numRealizedArguments())
					keep.put(fi.getTarget(), fi);
			}
		}
		if(!someViolation)
			return input;
		else {
			List<FrameInstance> fis = new ArrayList<FrameInstance>(keep.values());
			return new FNTagging(input.getSentence(), fis);
		}
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
		
		timer.start("initHypotheses");
		
		// make sure that we don't have overlapping targets
		frames = filterOutTargetCollisions(frames);
		
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
		timer.stop("initHypotheses");
	}
	
	private static void fill2d(double[][] mat, double val) {
		for(int i=0; i<mat.length; i++)
			Arrays.fill(mat[i], val);
	}

	@Override
	public ParsingSentenceDecodable runInference(FgModel model, FgInferencerFactory infFactory) {
		FgExample fg = getExample(false, true);
		return new RIDDecodable(fg.getFgLatPred(), infFactory, sentence, hypotheses, params);
	}

	private static class RIDDecodable extends ParsingSentenceDecodable {

		private Sentence sent;
		private List<RoleVars> hypotheses;
		private ParserParams params;
		
		public RIDDecodable(FactorGraph fg, FgInferencerFactory infFact, Sentence sent, List<RoleVars> hypotheses, ParserParams params) {
			super(fg, infFact);
			this.sent = sent;
			this.hypotheses = hypotheses;
			this.params = params;
		}

		@Override
		public FNParse decode() {
			FgInferencer inf = getMargins();
			List<FrameInstance> fis = new ArrayList<FrameInstance>();
			for(RoleVars rv : hypotheses)
				fis.add(decodeRoleVars(rv, inf, sent, params));
			return new FNParse(sent, fis);
		}
		
	}
	
	public static FrameInstance decodeRoleVars(RoleVars rv, FgInferencer inf, Sentence sentence, ParserParams params) {
		
		timer.start("decode");

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
				if(params.predictHeadValuedArguments)
					arguments[k] = Span.widthOne(jHat);
				else {
					Var expansionVar = rv.getExpansionVar(jHat, k);
					DenseFactor df = inf.getMarginals(expansionVar);
					arguments[k] = rv.getArgSpanFor(df.getArgmaxConfigId(), jHat, k);
				}
			}
		}

		timer.stop("decode");
		return FrameInstance.newFrameInstance(rv.getFrame(), Span.widthOne(rv.getTargetHead()), arguments, sentence);
	}

}
