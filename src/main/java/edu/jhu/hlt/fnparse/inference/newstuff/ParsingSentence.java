package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FactorGraph.FgEdge;
import edu.jhu.gm.model.FactorGraph.FgNode;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.PosUtil;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.experiment.ArgHeadPruning;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.Mode;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;
import edu.jhu.hlt.fnparse.util.Counts;
import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.morph.WordnetStemmer;

public class ParsingSentence {

	public static class FgExample extends edu.jhu.gm.data.FgExample {
		private static transient final long serialVersionUID = 1L;
		public final ParsingSentence cameFrom;
		public FgExample(FactorGraph fg, VarConfig goldConfig, ParsingSentence cameFrome) {
			super(fg, goldConfig);
			this.cameFrom = cameFrome;
		}
	}
	
	private static final boolean debugTargetRecall = false;
	private static final boolean debugDecodePart1 = true;	// frame decode
	private static final boolean debugDecodePart2 = true;	// arg decode
	
	public static final int maxLexPrototypesPerFrame = 30;
	

	// ==== VARIABLES ====
	public FrameVar[] frameVars;	// these store the labels for the roles as well
	public RoleVars[][][] roleVars;	// indexed as [i][j][k], i=target head, j=arg head, k=frame role idx
	public ProjDepTreeFactor depTree;	// holds variables too
	
	// ==== FACTORS ====
	private List<FactorFactory> factorTemplates;
	private List<Factor> factors;
	
	// ==== MISC ====
	public Sentence sentence;
	public FactorGraph fg;
	private VarConfig gold;
	private ParserParams params;	
	
	public ParsingSentence(Sentence s, ParserParams params) {
		this.params = params;
		this.factorTemplates = params.factors;
		this.sentence = s;
		
		final int n = sentence.size();
		frameVars = new FrameVar[n];
		for(int i=0; i<n; i++)
			frameVars[i] = makeFrameVar(sentence, i, params.logDomain);
	}
	
	
	/**
	 * NOTE: this should be called before setupRoleVars().
	 * @param p
	 */
	public void setGold(FNParse p, boolean clampFrameVars) {
		
		if(p.getSentence() != sentence)
			throw new IllegalArgumentException();
		if(frameVars == null)
			throw new IllegalStateException("did you call setupFrameVars()?");
		
		// set all the labels to nullFrame/nullSpan, and then overwrite those that aren't null
		final int n = p.getSentence().size();
		for(int i=0; i<n; i++) {
			if(frameVars[i] == null) continue;
			FrameInstance fiNull = FrameVar.nullFrameInstance(sentence, i);
			frameVars[i].setGold(fiNull);
		}

		// set the non-nullFrame vars to their correct Frame (and target Span) values
		for(FrameInstance fi : p.getFrameInstances()) {
			Span target = fi.getTarget();
			if(target.width() != 1)
				continue;
			FrameVar fv = frameVars[target.start];
			if(fv == null) continue;
			fv.setGold(fi);
			if(clampFrameVars)
				fv.clamp(fi.getFrame());
		}
	}
		

	/**
	 * looks at the current state of frameVars for the set of roles need to be created,
	 * and optionally the gold arg labels if they're there.
	 * 
	 * if not latent, then you must have already called setGold().
	 * 
	 * @return HACK: a map with the keys "kept" and "possible" representing the number of
	 * variables that would be kept, **under the new binary variable scheme**, and would
	 * be kept naively (aka possible) respectively.
	 */
	public Map<String, Integer> setupRoleVars() {
		
		// if you want to turn off this HACK, set ret to null
		Map<String, Integer> ret = new HashMap<String, Integer>();
		int kept = 0, possible = 0;
		// an arg can be:
		// 1) pruning its Frame
		// 2) pruning its arg head
		// 3) being ok
		// we can't see stage see arguments that fell into category 1 right now
		int argsPruned = 0, argsKept = 0;
		
		final int n = sentence.size();
		roleVars = new RoleVars[n][][];
		for(int i=0; i<n; i++) {
			FrameVar fi = frameVars[i];
			if(fi == null) continue;	// no frame => no args
			
			if(fi.getFrames().size() == 1 && fi.getFrame(0) == Frame.nullFrame) {
				assert params.mode == Mode.PIPELINE_FRAME_ARG : "FRAME_ID shouldn't bother with role vars";
				continue;
			}
			
			FrameInstance goldFI = fi.getGold();
		
			
			
			// HACK FOR SIDE EXPERIMENT:
			if(ret != null) {
				for(Frame f : fi.getFrames()) {
					int K = f.numRoles();
					for(int k=0; k<K; k++) {
						
						// setup: can we reach the gold argument?
						Span goldSpan = f == goldFI.getFrame()
							? goldFI.getArgument(k)
							: null;
						int goldHead = goldSpan == null || goldSpan == Span.nullSpan
								? -1 : params.headFinder.head(goldSpan, sentence);
						boolean canReachGoldArg = goldSpan == Span.nullSpan;
						
						// count how many role vars we pruned
						for(int j=0; j<n; j++) {
							possible++;
							if(!params.argPruner.pruneArgHead(f, k, j, sentence)) {
								kept++;
								if(j == goldHead)
									canReachGoldArg = true;
							}
						}
						
						if(goldSpan != null) {
							if(canReachGoldArg)
								argsKept++;
							else
								argsPruned++;
						}
					}
				}
				continue;	// don't bother with real variable setup
				// TODO REMOVE THIS
			}
			
			
			int K = fi.getMaxRoles();
			roleVars[i] = new RoleVars[n][K];
			for(int k=0; k<K; k++) {

				VarType r_ijkType;
				Span roleKspan;
				int roleKhead;
				
				if(goldFI == null) {
					// NO LABELS (PREDICTION)
					r_ijkType = VarType.PREDICTED;
					roleKspan = null;
					roleKhead = -1;
				}
				else {
					// HAVE LABELS (TRAINING)
					if(params.mode == Mode.PIPELINE_FRAME_ARG) {
						// for pipeline training, check that we predicted the correct frame,
						// and if not, set the arg labels to nullSpan (to improve precision at no cost to recall).
						if(fi.getFrames().size() != 1)
							throw new IllegalStateException();
						boolean predictedWrongFrame = fi.getFrame(0) != goldFI.getFrame();
						r_ijkType = VarType.PREDICTED;
						if(predictedWrongFrame) {
							roleKspan = Span.nullSpan;
							roleKhead = -1;
						}
						else {
							roleKspan = goldFI.getArgument(k);
							roleKhead = roleKspan != Span.nullSpan
									? params.headFinder.head(roleKspan, sentence)
									: -1;
						}
					}
					else {
						assert params.mode == Mode.JOINT_FRAME_ARG : "FRAME_ID shouldn't bother with role vars";
						if(k >= goldFI.getFrame().numRoles()) {
							r_ijkType = VarType.LATENT;
							roleKspan = Span.nullSpan;
							roleKhead = -1;
						}
						else {
							r_ijkType = VarType.PREDICTED;
							roleKspan = goldFI.getArgument(k);
							roleKhead = roleKspan != Span.nullSpan
									? params.headFinder.head(roleKspan, sentence)
									: -1;
						}
					}
				}
				
				for(int j=0; j<n; j++) {
					if(pruneArgHead(j, fi)) continue;	// TODO replace with params.argPruner.prune()
					RoleVars rv = RoleVars.tryToSetup(r_ijkType, fi.getFrames(), sentence, i, j, k, params.logDomain);
					if(rv == null) continue;
					roleVars[i][j][k] = rv;
					if(r_ijkType == VarType.PREDICTED) {	// set gold
						if(roleKhead == j)
							rv.setGold(goldFI.getFrame(), roleKspan);
						else
							rv.setGoldIsNull();
					}
				}
				
			}
		}
		if(ret != null) {
			ret.put("kept", kept);
			ret.put("possible", possible);
			ret.put("argsKept", argsKept);
			ret.put("argsPruned", argsPruned);
		}
		return ret;
	}
	
	
	/**
	 * clamps frame variable at the decoded value
	 */
	public FNTagging decodeFrames(FgModel model, FgInferencerFactory infFactory) {

		if(params.mode == Mode.JOINT_FRAME_ARG)
			setupRoleVars();
		
		FgExample fge = this.getFgExample();
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
	
	
	public FNParse decodeArgs(FgModel model, FgInferencerFactory infFactory) {

		if(debugDecodePart2 && params.debug) {
			System.out.printf("[decode part2] fpPen=%.3f fnPen=%.3f\n",
					params.argDecoder.getFalsePosPenalty(), params.argDecoder.getFalseNegPenalty());
		}
		if(params.mode == Mode.FRAME_ID)
			throw new IllegalStateException();
		
		// now that we've clamped the f_i at our predictions,
		// there will be much fewer r_ijk to instantiate.
		setupRoleVars();

		FgExample fge = this.getFgExample();
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

	
	protected boolean pruneArgHead(int j, FrameVar f_i) {
		String pos = sentence.getPos(j);
		return pos.endsWith("DT") || ArgHeadPruning.pennPunctuationPosTags.contains(pos);
	}

	
	/**
	 * based on our target extraction and possible frame triage,
	 * what is the best recall we could hope to get?
	 */
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
		FrameVar fv = frameVars[target.start];
		if(fv == null) return false;
		return fv.getFrames().contains(fi.getFrame());
	}
	
	
	/**
	 * Given a word in a sentence, extract the set of frames it might evoke.
	 * Basic idea: given a target with head word t, include any frame f s.t.
	 * lemma(t) == lemma(f.target)
	 */
	public FrameVar makeFrameVar(Sentence s, int headIdx, boolean logDomain) {
		
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
		
		if(debugTargetRecall && params.debug) {
			System.out.printf("[ParsingSentence makeFrameVar] #frames-from-LEX=%d #frames-from-LUs=%d\n",
					framesFromLexExamples, listedAsLUs.size());
			System.out.printf("[ParsingSentence makeFrameVar] trigger=%s frames=%s\n",
					s.getLU(headIdx), frameMatches);
			System.out.printf("[ParsingSentence makeFrameVar] trigger=%s prototypes=%s\n",
					s.getLU(headIdx), prototypes);
		}
		
		return new FrameVar(headIdx, prototypes, frameMatches, params);
	}
	
	
	public FgExample getFgExample() {
		
		int n = this.sentence.size();
		this.fg = new FactorGraph();
		this.gold = new VarConfig();
		
		// create factors
		factors = new ArrayList<Factor>();
		if(params.useLatentDepenencies)
			factors.add(depTree);
		for(FactorFactory ff : factorTemplates)
			factors.addAll(ff.initFactorsFor(sentence, frameVars, roleVars, depTree));
		
		// register all the variables and factors
		for(int i=0; i<n; i++) {
			if(frameVars[i] ==  null)
				continue;
			frameVars[i].register(fg, gold);
		}
		if(roleVars != null) {
			for(int i=0; i<n; i++) {
				if(roleVars[i] == null)
					continue;
				for(int j=0; j<n; j++) {
					for(int k=0; k<roleVars[i][j].length; k++) {
						RoleVars rv = roleVars[i][j][k];
						if(rv != null)
							rv.register(fg, gold);
					}
				}
			}
		}

		// add factors to the factor graph
		for(Factor f : factors)
			fg.addFactor(f);

		return new FgExample(fg, gold, this);
	}
	
}

