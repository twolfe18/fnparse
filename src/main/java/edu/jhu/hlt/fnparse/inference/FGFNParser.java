package edu.jhu.hlt.fnparse.inference;

import java.util.*;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleMemoryStore;
import edu.jhu.gm.decode.MbrDecoder;
import edu.jhu.gm.decode.MbrDecoder.MbrDecoderPrm;
import edu.jhu.gm.inf.BeliefPropagation.BeliefPropagationPrm;
import edu.jhu.gm.inf.BeliefPropagation.BpScheduleType;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FeExpFamFactor;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.train.CrfObjective;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.BasicFrameRoleFeatures;
import edu.jhu.hlt.fnparse.inference.factors.FrameFactor;
import edu.jhu.hlt.fnparse.inference.factors.FrameRoleFactor;
import edu.jhu.hlt.fnparse.inference.factors.NumRoleHardFactor;
import edu.jhu.hlt.fnparse.inference.spans.ExhaustiveSpanExtractor;
import edu.jhu.hlt.fnparse.inference.spans.SingleWordSpanExtractor;
import edu.jhu.hlt.fnparse.inference.spans.SpanExtractor;
import edu.jhu.hlt.fnparse.inference.variables.ExhaustiveRoleHypothesisFactory;
import edu.jhu.hlt.fnparse.inference.variables.FrameHypothesis;
import edu.jhu.hlt.fnparse.inference.variables.FrameHypothesisFactory;
import edu.jhu.hlt.fnparse.inference.variables.RoleHypothesis;
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
	
	public FrameFactor.Features getFrameFeatures() { return frameFeats; }
	public FrameRoleFactor.Features getFrameRoleFeatures() { return frameRoleFeatures; }
	
	public SpanExtractor getTargetIdentifier() { return targetIdentifier; }
	public FrameHypothesisFactory getFrameIdentifier() { return frameHypFactory; }
	public RoleHypothesisFactory<?> getRoleIdentifier() { return roleHypFactory; }
	
	public void train(List<Sentence> examples) {
		
		CrfTrainer.CrfTrainerPrm trainerPrm = new CrfTrainer.CrfTrainerPrm();
		CrfTrainer trainer = new CrfTrainer(trainerPrm);
		
		FgExampleMemoryStore exs = new FgExampleMemoryStore();
		for(Sentence s : examples) {
			FGFNParserSentence ps = new FGFNParserSentence(s);
			exs.add(new FgExample(ps.fg, ps.goldConf));
		}
		
		// TODO: Feature name tracking is done by me.
		int numParams = frameFeats.cardinality() + frameRoleFeatures.cardinality();
		this.model = new FgModel(numParams);
		this.model = trainer.train(this.model, exs);
	}
	
	public double getLogLikelihood(List<Sentence> examples, boolean startWithZeroedParams) {
		
		System.out.printf("[getLogLikelihood] getting LL for %d sentences\n", examples.size());
		for(Sentence s : examples) System.out.print(" " + s.size());
		System.out.println();
		
		Logger l = Logger.getLogger(FgExample.class);
		l.setLevel(Level.ALL);
		l.info("a test from travis' code");
		
		CrfTrainer.CrfTrainerPrm trainerPrm = new CrfTrainer.CrfTrainerPrm();
		BeliefPropagationPrm bpPrm = new BeliefPropagationPrm();
		bpPrm.logDomain = false;
		bpPrm.schedule = BpScheduleType.TREE_LIKE;
		trainerPrm.infFactory = bpPrm;
		FgExampleMemoryStore exs = new FgExampleMemoryStore();
		for(Sentence s : examples) {
			FGFNParserSentence ps = new FGFNParserSentence(s);
			ps.printStatus();
			System.out.println();
			exs.add(new FgExample(ps.fg, ps.goldConf));
		}
		
		if(startWithZeroedParams) {
			int numParams = frameFeats.cardinality() + frameRoleFeatures.cardinality();
			this.model = new FgModel(numParams);
		}
		else if(model == null)
			throw new IllegalStateException("either train or use zeroed params");
		
		CrfObjective objective = new CrfObjective(trainerPrm.crfObjPrm, model, exs, trainerPrm.infFactory);
		return objective.getValue();
	}

	public List<Sentence> parse(List<Sentence> sentences) {
		List<Sentence> ret = new ArrayList<Sentence>();
		for(Sentence inSent : sentences) {
			Sentence s = inSent.copy(false);
			FGFNParserSentence ps = new FGFNParserSentence(s);
			ps.decode(model);
			ret.add(s);
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
	
	/**
	 * Represents the factor graph for a sentence, most of the meat is here.
	 */
	public class FGFNParserSentence {
		
		public static final boolean verbose = false;
		
		public List<FrameHypothesis> frameVars;
		public List<List<RoleHypothesis>> roleVars;	// first index corresponds to frameVars, second is (roleIdx x spans)
		public List<FeExpFamFactor> frameFactors;
		public List<FeExpFamFactor> frameRoleFactors;
		
		public Sentence sentence;
		public FactorGraph fg;
		public VarConfig goldConf;
		
		public DParseVars dParseVars;
		public CParseVars cParseVars;
		
		public void printStatus() {
			System.out.printf("FGFNParserSentence of size %d\n", sentence.size());
			double totalFramesPossible = 0d;
			for(FrameHypothesis fh : frameVars)
				totalFramesPossible += fh.numPossibleFrames();
			System.out.printf("there are %d frame vars, with an average of %.1f frames/target and %.1f targets/word\n",
					frameVars.size(), totalFramesPossible/frameVars.size(), ((double)frameVars.size())/sentence.size());
			int totalRoleVars = 0;
			int X = 0;
			for(int i=0; i<frameVars.size(); i++) {
				List<RoleHypothesis> lrh = roleVars.get(i);
				int numRoles = 0;
				for(RoleHypothesis rh : lrh) {	// spans X roles
					if(rh.getRoleIdx() > numRoles)
						numRoles = rh.getRoleIdx();
				}
				X += numRoles+1;	// +1 to go from 0-indexing to counts
				totalRoleVars += lrh.size();
			}
			double spansPerRole = totalRoleVars / X;
			System.out.printf("there are %d role-span vars, with an average of %.1f roles-spans/frame and %.1f spans/frame-role\n",
					totalRoleVars, ((double)totalRoleVars) / roleVars.size(), spansPerRole);
			System.out.printf("n*(n-1)/2 = %d\n", sentence.size() * (sentence.size()-1) / 2);
		}
		
		public FGFNParserSentence(Sentence s) {
			
			long start = System.currentTimeMillis();
			this.goldConf = new VarConfig();
			this.sentence = s;
			this.fg = new FactorGraph();
			this.cParseVars = new DummyConstitParse(s);
			this.dParseVars = null;	// TODO
			
			// build an index so you can look up if there is a Frame evoked at a particular Span
			Map<Span, FrameInstance> goldFrames = new HashMap<Span, FrameInstance>();
			for(FrameInstance fi : s.getFrameInstances())
				goldFrames.put(fi.getTarget(), fi);
			
			// targets and frameHyps
			frameVars = new ArrayList<FrameHypothesis>();
			frameFactors = new ArrayList<FeExpFamFactor>();
			for(Span sp : targetIdentifier.computeSpans(s)) {
				FrameHypothesis f_i = frameHypFactory.make(sp, goldFrames.get(sp), sentence);
				if(f_i.numPossibleFrames() == 1) {
					assert f_i.getPossibleFrame(0) == Frame.nullFrame;
					continue;
				}
				assert f_i.numPossibleFrames() > 1;
				FrameFactor ff_i = new FrameFactor(f_i, frameFeatExtr);
				frameVars.add(f_i);
				frameFactors.add(ff_i);
				fg.addFactor(ff_i);
				fg.addVar(f_i.getVar());
				
				Integer goldFrameIdx = f_i.getGoldFrameIndex();
				if(goldFrameIdx != null) {
					if(verbose)
						System.out.printf("[goldConfig.put] F: %s -> %s\n", f_i.getVar(), goldFrameIdx);
					goldConf.put(f_i.getVar(), goldFrameIdx);
				}
				else
					System.out.println("WTF1");
			}
			assert goldConf.size() == frameVars.size() :
				String.format("goldConf.size=%d frameVars.size=%d", goldConf.size(), frameVars.size());
			System.out.printf("[FGFNParserSentence] target/frame init took %.2f\n", (System.currentTimeMillis()-start)/1000d);
			
			// arguments
			roleVars = new ArrayList<List<RoleHypothesis>>();
			frameRoleFactors = new ArrayList<FeExpFamFactor>();
			for(int i=0; i<frameVars.size(); i++) {
				FrameHypothesis f_i = frameVars.get(i);
			
				List<RoleHypothesis> roleVars_i = new ArrayList<RoleHypothesis>();
				roleVars.add(roleVars_i);
			
				int maxRoles = f_i.maxRoles();	// how many roles are needed for the highest-arity Frame
				for(int k=0; k<maxRoles; k++) {
					for(RoleHypothesis r_ijk : roleHypFactory.make(f_i, k, sentence, cParseVars)) {
				
						roleVars_i.add(r_ijk);
						FrameRoleFactor fr = new FrameRoleFactor(f_i, r_ijk, frameRoleFeatExtr);
						frameRoleFactors.add(fr);
						fg.addFactor(fr);
						fg.addVar(r_ijk.getVar());
						RoleHypothesis.Label gold = r_ijk.getGoldLabel();
						if(gold != RoleHypothesis.Label.UNK)
							goldConf.put(r_ijk.getVar(), gold.getInt());
						
						// add hard factors for checking #roles compatibility with frame
						final boolean useLogValues = true;
						NumRoleHardFactor hf = new NumRoleHardFactor(f_i, r_ijk, useLogValues);
						fg.addFactor(hf.getFactor());
					}
					System.out.print("*");
				}
			}
			System.out.println();
			System.out.printf("[FGFNParserSentence] init took %.2f seconds\n", (System.currentTimeMillis()-start)/1000d);
		}

		/**
		 * adds FrameInstances to the Sentence provided in the constructor
		 */
		public void decode(FgModel model) {
			
			if(sentence.numFrameInstances() > 0)
				throw new IllegalStateException("did you add an already annotated Sentence?");
			
			MbrDecoderPrm decPrm = new MbrDecoderPrm();
			MbrDecoder dec = new MbrDecoder(decPrm);
			
			// for now, pass goldConf. will blow up?
			dec.decode(model, new FgExample(this.fg, this.goldConf));
			
			VarConfig conf = dec.getMbrVarConfig();
			Map<Var, Double> margins = dec.getVarMargMap();
			for(int i=0; i<frameVars.size(); i++) {
				
				// read the frame that is evoked
				FrameHypothesis f_i = frameVars.get(i);
				int f_i_value = conf.getState(f_i.getVar());
				Frame f_i_hyp = f_i.getPossibleFrame(f_i_value);
				if(f_i_hyp == Frame.nullFrame)
					continue;
				
				int numRoles = f_i_hyp.numRoles();
				Span[] roles = new Span[numRoles];
				double[] confidences = new double[numRoles];	// initialized to 0
				for(RoleHypothesis r_ijk : roleVars.get(i)) {
					double r_ijk_prob = margins.get(r_ijk.getVar());
					int j = r_ijk.getRoleIdx();
					if(r_ijk_prob > confidences[j]) {
						roles[j] = r_ijk.getExtent();
						confidences[j] = r_ijk_prob;
					}
				}
				
				FrameInstance fi = FrameInstance.newFrameInstance(f_i_hyp, f_i.getTargetSpan(), roles, sentence);
				sentence.addFrameInstance(fi);
			}
		}
		
		public List<Var> getAllVariables() {
			List<Var> vars = new ArrayList<Var>();
			for(FrameHypothesis f : frameVars)
				vars.add(f.getVar());
			for(List<RoleHypothesis> lr : roleVars)
				for(RoleHypothesis r : lr)
					vars.add(r.getVar());
			if(dParseVars != null)
				for(Var v : dParseVars.getAllVariables())
					vars.add(v);
			if(cParseVars != null)
				for(Var v : cParseVars.getAllVariables())
					vars.add(v);
			return vars;
		}

	}
	
}


