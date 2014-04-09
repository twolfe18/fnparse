package edu.jhu.hlt.fnparse.inference.sentence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.PosUtil;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.newstuff.FactorFactory;
import edu.jhu.hlt.fnparse.inference.newstuff.FrameInstanceHypothesis;
import edu.jhu.hlt.fnparse.inference.newstuff.FrameVars;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;
import edu.jhu.hlt.fnparse.util.Counts;
import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.morph.WordnetStemmer;

/**
 * wraps the variables needed for training/predicting the factor graph
 * for FN parsing.
 * 
 * because FgExamples store pointers to Vars which are statefully used in this
 * class, if you want to create two FgExamples for training (e.g. for a pipeline
 * model), you need to create two ParsingSentences for the one underlying Sentence.
 * 
 * @author travis
 */
public abstract class ParsingSentence {

	/** like a regular FgExample, but stores a backpointer to the ParsingSentence it came from */
	public static class FgExample extends edu.jhu.gm.data.FgExample {
		private static transient final long serialVersionUID = 1L;
		public final ParsingSentence cameFrom;
		public FgExample(FactorGraph fg, VarConfig goldConfig, ParsingSentence cameFrome) {
			super(fg, goldConfig);
			this.cameFrom = cameFrome;
		}
	}
	
	
	// move this into ParserParams?
	public static final int maxLexPrototypesPerFrame = 30;
	

	// ==== VARIABLES ====
	public List<FrameInstanceHypothesis> frameRoleVars;
	public ProjDepTreeFactor depTree;	// holds variables too
	
	// ==== FACTORS ====
	protected List<FactorFactory> factorTemplates;
	protected List<Factor> factors;
	
	// ==== MISC ====
	public Sentence sentence;
	protected ParserParams params;
	
	public ParsingSentence(Sentence s, ParserParams params) {
		this.params = params;
		this.factorTemplates = params.factors;
		this.sentence = s;
		this.frameRoleVars = new ArrayList<FrameInstanceHypothesis>();
	}
	
	
	protected void setGold(FNTagging p) {	//, boolean clampFrameVars) {
			
		if(p.getSentence() != sentence)
			throw new IllegalArgumentException();
		
		// build an index from targetHeadIdx to FrameRoleVars
		Set<FrameInstanceHypothesis> haventSet = new HashSet<FrameInstanceHypothesis>();
		FrameInstanceHypothesis[] byHead = new FrameInstanceHypothesis[sentence.size()];
		for(FrameInstanceHypothesis fHyp : this.frameRoleVars) {
			byHead[fHyp.getTargetHeadIdx()] = fHyp;
			haventSet.add(fHyp);
		}
		
		// match up each FI to a FIHypothesis by the head word in the target
		for(FrameInstance fi : p.getFrameInstances()) {
			Span target = fi.getTarget();
			int head = params.headFinder.head(target, sentence);
			FrameInstanceHypothesis fHyp = byHead[head];
			if(fHyp == null) continue;	// nothing you can do here
			fHyp.setGold(fi);
			boolean added = haventSet.add(fHyp);
			assert added : "two FrameInstances with same head?";
		}
		
		// the remaining hypotheses must be null because they didn't correspond to a FI in the parse
		for(FrameInstanceHypothesis fHyp : haventSet)
			fHyp.setGoldIsNull();
	}
	
	// i should expose the following methods
	// these methods are meant to generalize over the types of inference
	
	// how am i going to do setup for
	// pipeline (2 steps) vs joint (1 step)?
	// => simple, just setup for the first pass and in decode you will need to do another step
	
	/* reads the type of setup needed from parameters
	private void setupVars() {
		frameRoleVars.clear();
		final int n = sentence.size();
		for(int i=0; i<n; i++) {
			FrameVars fv = makeFrameVar(sentence, i, params.logDomain);
			if(fv == null) continue;
			FrameInstanceHypothesis fhyp = new FrameInstanceHypothesis(sentence, fv, params);
			this.frameRoleVars.add(fhyp);
			if(params.mode == Mode.FRAME_ID) continue;
			if(params.mode == Mode.JOINT_FRAME_ARG)
				fhyp.setupRoles(VarType.PREDICTED);
			else {
				assert params.mode == Mode.PIPELINE_FRAME_ARG;
				fhyp.setupRoles(VarType.LATENT);
			}
			
		}
	}
	*/


	/** might return a FNParse depending on the settings */
	public abstract FNTagging decode();
	
	public abstract FgExample getTrainingExample();


	/*
	 * clamps frame variable at the decoded value
	private FNTagging decodeFrames(FgModel model, FgInferencerFactory infFactory) {

		if(params.mode == Mode.JOINT_FRAME_ARG)
			setupRoleVars();
		
		FgExample fge = this.makeFgExample();
		FactorGraph fg = fge.updateFgLatPred(model, params.logDomain);
		BeliefPropagation inf = (BeliefPropagation) infFactory.getInferencer(fg);
		inf.run();
		
		List<FrameInstance> fis = new ArrayList<FrameInstance>();
		final int n = sentence.size();
		for(int i=0; i<n; i++) {
			FrameVar fv = frameVars[i];
			if(fv == null) continue;
			
			DenseFactor df = inf.getMarginals(fv.getFrameVar());
			int nullFrameIdx = 0;
			assert fv.getFrame(nullFrameIdx) == Frame.nullFrame;
			int f_dec_idx = params.frameDecoder.decode(df.getValues(), nullFrameIdx);

			if(debugDecodePart1 && params.debug) {
				System.out.println("margins at " + i + " = " + df);
				System.out.println("frame options: " + fv.getFrames());
				
				FgNode f_i_node = fg.getNode(fv.getFrameVar());
				for(FgEdge evidence : f_i_node.getInEdges()) {
					System.out.println("evidence: " + evidence);
					System.out.println("msg: " + inf.getMessages()[evidence.getId()].message);
				}
				
				System.out.println();
			}
			
			Frame fhat = fv.getFrame(f_dec_idx);
			fv.clamp(fhat);
			
			if(fhat != Frame.nullFrame)
				fis.add(FrameInstance.frameMention(fhat, Span.widthOne(i), sentence));
		}
		return new FNTagging(sentence, fis);
	}
	 */
	
	
	/*
	private FNParse decodeArgs(FgModel model, FgInferencerFactory infFactory) {

		if(debugDecodePart2 && params.debug) {
			System.out.printf("[decode part2] fpPen=%.3f fnPen=%.3f\n",
					params.argDecoder.getFalsePosPenalty(), params.argDecoder.getFalseNegPenalty());
		}
		if(params.mode == Mode.FRAME_ID)
			throw new IllegalStateException();
		
		// now that we've clamped the f_i at our predictions,
		// there will be much fewer r_ijk to instantiate.
		setupRoleVars();

		FgExample fge = this.makeFgExample();
		fge.updateFgLatPred(model, params.logDomain);
		FgInferencer inf = infFactory.getInferencer(fge.getOriginalFactorGraph());
		inf.run();

		List<FrameInstance> fis = new ArrayList<FrameInstance>();
		final int n = sentence.size();
		for(int i=0; i<n; i++) {

			FrameVar fv = frameVars[i];
			if(fv == null) continue;
			Frame f = fv.getFrame(0);
			if(f == Frame.nullFrame) continue;

			Span[] args = new Span[f.numRoles()];
			Arrays.fill(args, Span.nullSpan);

			// for each role, choose the most sensible realization of this role
			for(int k=0; k<f.numRoles(); k++) {

				// we are going to decode every r_ijk separately (they're all binary variables for arg realized or not)
				// the only time you have a problem is when more than one r_ijk is decoded (given i,k ranging over j)
				// among these cases, choose the positive r_ijk with the smallest risk
				List<Integer> active = new ArrayList<Integer>();
				List<Double> risks = new ArrayList<Double>();
				double[] riskBuf = new double[2];
				for(int j=0; j<n; j++) {
					RoleVars r_ijk = roleVars[i][j][k];
					if(r_ijk == null) continue;
					DenseFactor df = inf.getMarginals(r_ijk.getRoleVar());
					int nullIndex = 0;
					assert r_ijk.getFrame(nullIndex) == Frame.nullFrame;
					int r_ijk_dec = params.argDecoder.decode(df.getValues(), nullIndex, riskBuf);
					assert r_ijk.getPossibleFrames().size() == 2;
					boolean argIsRealized = r_ijk_dec != nullIndex;
					if(argIsRealized) {
						active.add(j);
						risks.add(riskBuf[r_ijk_dec]);
					}
					if(debugDecodePart2 && params.debug) {
						System.out.printf("[decode part2] %s.%s = %s risks: %s\n",
								f.getName(), f.getRole(k), sentence.getLU(j), Arrays.toString(riskBuf));
					}
				}

				if(active.size() == 0)
					args[k] = Span.nullSpan;
				else  {
					int j = -1;
					if(active.size() == 1) {
						j = active.get(0);
						if(debugDecodePart2 && params.debug)
							System.out.println("[decode part2] unabiguous min risk: " + sentence.getLU(j).getFullString());
					}
					else {
						// have to choose which index has the lowest (marginal) risk
						if(debugDecodePart2 && params.debug) {
							System.out.printf("[decode part2] more than one token has min risk @ arg realized. indices=%s risks=%s\n",
									active, risks);
						}
						double minR = 0d;
						for(int ji=0; ji<active.size(); ji++) {
							double r = risks.get(ji);
							if(r < minR || j < 0) {
								minR = r;
								j = active.get(ji);
							}
						}
					}
					// choose the most likely expansion/span conditioned on the arg head index
					RoleVars r_ijk = roleVars[i][j][k];
					DenseFactor df = inf.getMarginals(r_ijk.getExpansionVar());
					if(debugDecodePart2 && params.debug) {
						System.out.println("[decode part2] expansion marginals: " + Arrays.toString(df.getValues()));
					}
					int expansionConfig = df.getArgmaxConfigId();
					args[k] = r_ijk.getSpan(expansionConfig);
				}
			}
			fis.add(FrameInstance.newFrameInstance(f, Span.widthOne(i), args, sentence));
		}

		return new FNParse(sentence, fis);
	}
	*/
	
	/*
	 * based on our target extraction and possible frame triage,
	 * what is the best recall we could hope to get?
	public double computeMaxTargetRecall(FNParse p) {
		int reachable = 0, total = 0;
		for(FrameInstance fi : p.getFrameInstances()) {
			if(couldRecallTarget(fi)) reachable++;
			total++;
		}
		if(total == 0) return 1d;
		return ((double)reachable) / total;
	}
	private boolean couldRecallTarget(FrameInstance fi) {
		Span target = fi.getTarget();
		if(target.width() > 1) return false;
		FrameVars fv = frameVars[target.start];
		if(fv == null) return false;
		return fv.getFrames().contains(fi.getFrame());
	}
	 */
	
	
	/**
	 * Given a word in a sentence, extract the set of frames it might evoke.
	 * Basic idea: given a target with head word t, include any frame f s.t.
	 * lemma(t) == lemma(f.target)
	 */
	private FrameVars makeFrameVar(Sentence s, int headIdx, boolean logDomain) {
		
		if(params.targetPruningData.prune(headIdx, s))
			return null;

		Set<Frame> uniqFrames = new HashSet<Frame>();
		List<Frame> frameMatches = new ArrayList<Frame>();
		List<FrameInstance> prototypes = new ArrayList<FrameInstance>();
		
		prototypes.addAll(FrameInstance.Prototype.miscPrototypes());
		frameMatches.add(Frame.nullFrame);
		
		// we need to limit the number of prototypes per frame
		Counts<Frame> numPrototypes = new Counts<Frame>();
		
		// get prototypes/frames from the LEX examples
		Map<String, List<FrameInstance>> stem2prototypes = params.targetPruningData.getPrototypesByStem();
		IRAMDictionary wnDict = params.targetPruningData.getWordnetDict();
		WordnetStemmer stemmer = new WordnetStemmer(wnDict);
		String word = s.getWord(headIdx);
		POS pos = PosUtil.ptb2wordNet(s.getPos(headIdx));
		for(String stem : stemmer.findStems(word, pos)) {
			List<FrameInstance> protos = stem2prototypes.get(stem);
			if(protos == null) continue;
			for(FrameInstance fi : protos) {
				Frame f = fi.getFrame();
				int c = numPrototypes.increment(f);
				if(c > maxLexPrototypesPerFrame) continue;	// TODO reservoir sample
				if(uniqFrames.add(f)) {
					frameMatches.add(f);
					prototypes.add(fi);
				}
			}
		}
		int framesFromLexExamples = frameMatches.size();
		
		// get frames that list this as an LU
		LexicalUnit fnLU = s.getFNStyleLU(headIdx, params.targetPruningData.getWordnetDict());
		List<Frame> listedAsLUs = params.targetPruningData.getFramesFromLU(fnLU);
		for(Frame f : listedAsLUs) {
			if(uniqFrames.add(f)) {
				frameMatches.add(f);
				//prototypes.add(???);
			}
		}
		// infrequently, stemming messes up, "means" is listed for the Means frame, but "mean" isn't
		for(Frame f : params.targetPruningData.getFramesFromLU(s.getLU(headIdx))) {
			if(uniqFrames.add(f)) {
				frameMatches.add(f);
				//prototypes.add(???);
			}
		}
		
		if(frameMatches.size() == 1)	// nullFrame
			return null;
		
		if(params.debug) {
			System.out.printf("[ParsingSentence makeFrameVar] #frames-from-LEX=%d #frames-from-LUs=%d\n",
					framesFromLexExamples, listedAsLUs.size());
			System.out.printf("[ParsingSentence makeFrameVar] trigger=%s frames=%s\n",
					s.getLU(headIdx), frameMatches);
			System.out.printf("[ParsingSentence makeFrameVar] trigger=%s prototypes=%s\n",
					s.getLU(headIdx), prototypes);
		}
		
		return new FrameVars(headIdx, prototypes, frameMatches, params);
	}
	
	
	/* replaced by getTrainingExample above,
	 * this implementation will vary by subclass
	public FgExample makeFgExample() {
		
		FactorGraph fg = new FactorGraph();
		VarConfig gold = new VarConfig();
		
		// create factors
		factors = new ArrayList<Factor>();
		if(params.useLatentDepenencies)
			factors.add(depTree);
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
	 */
	
}

