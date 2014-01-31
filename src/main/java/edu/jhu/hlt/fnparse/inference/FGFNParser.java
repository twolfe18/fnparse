package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleMemoryStore;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.BasicFrameRoleFeatures;
import edu.jhu.hlt.fnparse.inference.factors.FrameFactor;
import edu.jhu.hlt.fnparse.inference.factors.FrameRoleFactor;
import edu.jhu.hlt.fnparse.inference.spans.ExhaustiveSpanExtractor;
import edu.jhu.hlt.fnparse.inference.spans.SingleWordSpanExtractor;
import edu.jhu.hlt.fnparse.inference.spans.SpanExtractor;
import edu.jhu.hlt.fnparse.inference.variables.ExhaustiveRoleHypothesisFactory;
import edu.jhu.hlt.fnparse.inference.variables.FrameHypothesisFactory;
import edu.jhu.hlt.fnparse.inference.variables.RoleHypothesisFactory;
import edu.jhu.hlt.fnparse.inference.variables.SemaforicFrameHypothesisFactory;

/**
 ******************** Factor graph model that is similar to SEMAFOR ********************
 * 
 * For a sentence with length n, having s spans:
 * 
 * One "target" variable per word in a sentence which describes the frame that
 * is evoked by that target word:
 *   [f_1, f_2, ... f_n]
 * 
 * If the frame which with the most roles has k roles, then we will
 * have k * n "role" variables which describe what span is the realization
 * of this frame (given by the target) and role:
 *   r_{i,j} \in [1, 2, ... s] \forall
 *     i \in [1, 2, ... n]	# word index
 *     j \in [1, 2, ... k]	# role
 * 
 * There will be unary factors on each f_i variable which is comparable
 * to the target identification log-linear model in SEMAFOR.
 * 
 * There will be binary factors that connect each frame target with each of its role variables:
 *   for i in range(n):
 *     for j in range(k):
 *       yield Factor(f_i, r_{i,j})
 * 
 * We will do decoding by starting with sum-product BP, and slowly anneal towards
 * max-product BP (viterbi).
 * 
 * If we want to train (relatively) quickly, as they did with SEMAFOR, we will
 * mimic piecewise training by first learning the target unary factor weights
 * by _not adding the role variables_, and doing maximum conditional likelihood training.
 * When it is time to train the target-role binary factors, we will clamp the target
 * variables at the predicted values, and proceed with maximum conditional likelihood training.
 * 
 * Concerning the constraint that two roles for a given target may not map to the same span,
 * we can first ignore this during training, and do decoding using beam search as in SEMAFOR.
 * A slower way to do this is to add a MutexFactor(r_{i,j}, r_{i,k}) \forall j < k.
 * 
 * Another issue to address is how to do span identification.
 * -> can prune spans in a variety of ways (NER tagger, constituents of parse, up to a certain width, etc).
 *    perhaps these should, in the exact case, be unary factors on target-role variables (factor only considers the span)
 * -> can choose to parameterize a span as (headIdx, leftExpand, rightExpand).
 *    this may mean that we can train more robust (target, role-head) factors/features
 * 
 * 
 * We might also want to try to break up the frame variables for the binary factors that connect
 * f_i and r_ij variables. Right now we need to loop over at least 2,000 frames times the number of spans (e.g. 500),
 * which will be very slow. We may be able to essentially do dimensionality reduction on the frames.
 * This would work by replacing the f_i variables with ones that range over "meta frames", with say 30 values.
 * These "meta frames" would then connect to all frames, but the 2,000*500 loop is broken down into 30*500 + 30*2000.
 * Choosing the "meta frame" to frame mapping could be done by looking at the frame relations, e.g.
 * if the frames formed a tree, the "meta frames" could constitute frames high up in the tree.
 * It would be interesting to look into clustering (dimensionality reduction?) on trees of items to
 * see if there is a good way to do this.
 * Note that this doesn't help if we do piecewise training and clamp the f_i variables (it only helps for the
 * f_i to r_ij factors). Also, the number of "meta frames" must be consistently smaller than then number of
 * spans in a sentence, otherwise it is cheaper to do it the naive way. In the context of good span pruning,
 * this may end up not being worth it.
 *   
 * TODO: add latent variable for LU, as in SEMAFOR
 */
public class FGFNParser {
	
	private FgModel model;
	
	private FrameFactor.Features frameFeats = new BasicFrameFeatures();
	private FrameFactor.FeatureExtractor frameFeatExtr = new FrameFactor.FeatureExtractor(frameFeats);
	
	private FrameRoleFactor.Features frameRoleFeatures = new BasicFrameRoleFeatures();
	private FrameRoleFactor.FeatureExtractor frameRoleFeatExtr = new FrameRoleFactor.FeatureExtractor(frameRoleFeatures);
	
	private SpanExtractor targetIdentifier = new SingleWordSpanExtractor();
	private FrameHypothesisFactory frameHypFactory = new SemaforicFrameHypothesisFactory();
	private RoleHypothesisFactory<CParseVars> roleHypFactory = new ExhaustiveRoleHypothesisFactory();
	
//	public String getName() {
//		StringBuilder sb = new StringBuilder("<FGFNParser_");
//		sb.append("targetId=" + targetIdentifier.getName());
//		sb.append("framesId=" + frameHypFactory.getName());
//		// TODO
//		sb.append(">");
//		return sb.toString();
//	}
	
	public FgModel getModel() { return model; }
	public FrameFactor.Features getFrameFeatures() { return frameFeats; }
	public FrameRoleFactor.Features getFrameRoleFeatures() { return frameRoleFeatures; }
	
	public SpanExtractor getTargetIdentifier() { return targetIdentifier; }
	public FrameHypothesisFactory getFrameIdentifier() { return frameHypFactory; }
	public RoleHypothesisFactory<?> getRoleIdentifier() { return roleHypFactory; }
	
	public void train(List<FNParse> examples, boolean jointTraining) {
		
		CrfTrainer.CrfTrainerPrm trainerPrm = new CrfTrainer.CrfTrainerPrm();
		CrfTrainer trainer = new CrfTrainer(trainerPrm);
		
		FgExampleMemoryStore exs = new FgExampleMemoryStore();
		for(FNParse parse : examples) {
			if(jointTraining) {
				FGFNParsing.JointParsing j = new FGFNParsing.JointParsing();
				exs.add(j.getTrainingInstance(parse));
			} else {
				FGFNParsing.FrameTagging f = new FGFNParsing.FrameTagging();
				exs.add(f.getTrainingInstance(parse));
				FGFNParsing.ArgumentTagging a = new FGFNParsing.ArgumentTagging();
				exs.add(a.getTrainingInstance(parse));
			}
		}
		
		// TODO: Feature name tracking is done by me.
		int numParams = frameFeats.cardinality() + frameRoleFeatures.cardinality();
		this.model = new FgModel(numParams);
		this.model = trainer.train(this.model, exs);
	}

	
//	public double getLogLikelihood(List<Sentence> examples, boolean startWithZeroedParams) {
//		
//		System.out.printf("[getLogLikelihood] getting LL for %d sentences\n", examples.size());
//		for(Sentence s : examples) System.out.print(" " + s.size());
//		System.out.println();
//		
//		Logger l = Logger.getLogger(FgExample.class);
//		l.setLevel(Level.ALL);
//		l.info("a test from travis' code");
//		
//		CrfTrainer.CrfTrainerPrm trainerPrm = new CrfTrainer.CrfTrainerPrm();
//		BeliefPropagationPrm bpPrm = new BeliefPropagationPrm();
//		bpPrm.logDomain = false;
//		bpPrm.schedule = BpScheduleType.TREE_LIKE;
//		trainerPrm.infFactory = bpPrm;
//		FgExampleMemoryStore exs = new FgExampleMemoryStore();
//		for(Sentence s : examples) {
//			FGFNParserSentence ps = new FGFNParserSentence(s);
//			ps.printStatus();
//			System.out.println();
//			exs.add(new FgExample(ps.fg, ps.goldConf));
//		}
//		
//		if(startWithZeroedParams) {
//			int numParams = frameFeats.cardinality() + frameRoleFeatures.cardinality();
//			this.model = new FgModel(numParams);
//		}
//		else if(model == null)
//			throw new IllegalStateException("either train or use zeroed params");
//		
//		CrfObjective objective = new CrfObjective(trainerPrm.crfObjPrm, model, exs, trainerPrm.infFactory);
//		return objective.getValue();
//	}

	public List<FNParse> parse(List<Sentence> sentences, boolean jointDecoding) {
		List<FNParse> ret = new ArrayList<FNParse>();
		for(Sentence sent : sentences) {
			if(jointDecoding) {
				FGFNParsing.JointParsing p = new FGFNParsing.JointParsing();
				ret.add(p.parse(sent));
			}
			else {
				FGFNParsing.FrameTagging f = new FGFNParsing.FrameTagging();
				FNTagging sentWithFrames = f.getFrames(sent);
				FGFNParsing.ArgumentTagging a = new FGFNParsing.ArgumentTagging();
				ret.add(a.getArguments(sentWithFrames));
			}
		}
		return ret;
	}

	// TODO factor out of Matt's code (SrlFactorGraph)
	public static interface DParseVars {
		Var getDependencyVar(int gov, int dep);
		Iterable<Var> getAllVariables();
	}
	
	// TODO implement this (Matt's implementation of CkyFactor may help)
	public static interface CParseVars {
		Var getConstituentVar(int from, int to);
		Iterable<Span> getAllConstituents();
		Iterable<Var> getAllVariables();
	}
	
	/**
	 * Has no variables, just provides the Spans via an ExhaustiveSpanExtractor
	 * @author travis
	 */
	public static class DummyConstitParse implements CParseVars {
		private Sentence sent;
		private SpanExtractor spanExtr = new ExhaustiveSpanExtractor();
		public DummyConstitParse(Sentence s) {
			this.sent = s;
		}
		@Override
		public Var getConstituentVar(int from, int to) {
			throw new RuntimeException("do i really have to implement this?");
		}
		@Override
		public Iterable<Span> getAllConstituents() { return spanExtr.computeSpans(sent); }
		@Override
		public Iterable<Var> getAllVariables() { return Collections.emptyList(); }
	}
	
}


