package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.*;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.*;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.inference.heads.*;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;
import edu.jhu.hlt.fnparse.util.*;
import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.morph.WordnetStemmer;

public class ParsingSentence {

	private static final boolean debugTargetRecall = false;
	private static final boolean debugDecodePart1 = true;	// frame decode
	private static final boolean debugDecodePart2 = true;	// arg decode
	
	public static final int maxLexPrototypesPerFrame = 30;
	

	// ==== VARIABLES ====
	public FrameVar[] frameVars;
	public RoleVars[][][] roleVars;	// indexed as [i][j][k], i=target head, j=arg head, k=frame role idx
	private ProjDepTreeFactor depTree;	// holds variables too
	
	// ==== FACTORS ====
	private List<FactorFactory> factorHolders;
	private List<Factor> factors;
	
	// ==== MISC ====
	private Sentence sentence;
	private FactorGraph fg;
	private VarConfig gold;
	private ParserParams params;
	private FrameFilteringStrategy frameFilterStrat;
	private HeadFinder hf = new BraindeadHeadFinder();	// TODO
	
	/**
	 * In training data:
	 * what do we do when we say that a target may evoke
	 * a frame in set F, but the true frame g \not\in F?
	 */
	static enum FrameFilteringStrategy {
		ALWAYS_INCLUDE_GOLD_FRAME,				// add g to F, might increase R at the cost of P
		USE_NULLFRAME_FOR_FILTERING_MISTAKES,	// set g = nullFrame, might increase P at the cost of R
	}
	
	// TODO: the code is not setup for ALWAYS_INCLUDE_GOLD_FRAME because
	// this requires seeing the gold label while constructing all the variables.
	
	
	public ParsingSentence(Sentence s, ParserParams params) {
		this.params = params;
		this.factorHolders = params.factors;
		this.sentence = s;
		this.frameFilterStrat = FrameFilteringStrategy.USE_NULLFRAME_FOR_FILTERING_MISTAKES;
		
		// we can setup frame vars now, role vars must wait
		final int n = s.size();
		frameVars = new FrameVar[n];
		for(int i=0; i<n; i++)
			frameVars[i] = makeFrameVar(s, i, params.logDomain);
	}
	
	
	public void initFactors() {
		factors = new ArrayList<Factor>();
		if(params.useLatentDepenencies)
			factors.add(depTree);
		for(FactorFactory ff : factorHolders)
			factors.addAll(ff.initFactorsFor(sentence, frameVars, roleVars, depTree));
	}
	
	
	/**
	 * all r_ijk are instantiated with arity of \max_f f.numRoles,
	 * and r_ijk corresponding to f_i == nullFrame are considered latent.
	 */
	public void setupRoleVarsForTrain(FNParse p) {
		
		// set the gold labels for all the f_i
		FrameInstance[] goldFiTargets = setGoldForFrameVars(p);
		
		// set the gold labels for all the r_ijk
		int n = sentence.size();
		roleVars = new RoleVars[n][][];
		for(int i=0; i<n; i++) {
			FrameVar fi = frameVars[i];
			if(fi == null) continue;	// no frame => no args
			
			FrameInstance goldFIat_i = goldFiTargets[i];
			if(goldFIat_i == null) assert fi.getGoldFrame() == Frame.nullFrame;
		
			int K = fi.getMaxRoles();
			roleVars[i] = new RoleVars[n][K];
			for(int k=0; k<K; k++) {

				// mark's point: make stuff that doesn't matter latent
				VarType r_ijkType = k >= fi.getGoldFrame().numRoles() || fi.getGoldFrame() == Frame.nullFrame
						? VarType.LATENT
						: VarType.PREDICTED;

				// set the correct head for this role
				Span roleKspan = k >= fi.getGoldFrame().numRoles()
						? Span.nullSpan
						: goldFIat_i.getArgument(k);
				int roleKhead = roleKspan == Span.nullSpan
						? -1
						: hf.head(roleKspan, sentence);
				
				for(int j=0; j<n; j++) {
					RoleVars rv = new RoleVars(r_ijkType, fi.getFrames(), sentence, i, j, k, params.logDomain);
					roleVars[i][j][k] = rv;
					if(r_ijkType == VarType.PREDICTED) {	// set gold
						if(roleKhead == j)
							rv.setGold(goldFIat_i.getFrame(), roleKspan);
						else
							rv.setGoldIsNull();
					}
				}
				
			}
		}
	}
	
	
	/**
	 * all r_ijk are instantiated as latent with arity of \max_f f.numRoles
	 */
	public void setupRoleVarsForDecode(VarType roleVarsLatentOrPredicted) {
		final int n = sentence.size();
		roleVars = new RoleVars[n][][];
		for(int i=0; i<n; i++) {
			FrameVar fv = frameVars[i];
			if(fv == null) continue;
			final int K = fv.getMaxRoles();
			roleVars[i] = new RoleVars[n][K];
			for(int j=0; j<n; j++)
				for(int k=0; k<K; k++)
					roleVars[i][j][k] = new RoleVars(roleVarsLatentOrPredicted, fv.getFrames(), sentence, i, j, k, params.logDomain);
		}
	}

	
	/**
	 * prune r_ijk corresponding to f_i == nullFrame,
 	 * the other r_ijk will be predicted and binary
	 */
	public void setupRoleVarsForRoleDecode(Frame[] decodedFrames) {
		final int n = sentence.size();
		roleVars = new RoleVars[n][][];
		for(int i=0; i<n; i++) {
			FrameVar fv = frameVars[i];
			Frame f = decodedFrames[i];
			if(fv == null || f == Frame.nullFrame)
				continue;
			List<Frame> possibleFrames = Arrays.asList(Frame.nullFrame, f);
			final int K = f.numRoles();
			roleVars[i] = new RoleVars[n][K];
			for(int j=0; j<n; j++) {
				for(int k=0; k<K; k++) {
					RoleVars rv = new RoleVars(VarType.PREDICTED, possibleFrames, sentence, i, j, k, params.logDomain);
					roleVars[i][j][k] = rv;
					
					// This is dumb: matt's code wants a label for all predicted variables,
					// irrespective if we're doing training or testing.
					// For now, just give it a dummy value; as far as I can tell this doesn't affect inference.
					rv.setGoldIsNull();
				}
			}
		}
	}

	
	/**
	 * This is a two step process:
	 * 1. decode f_i with r_ijk latent
	 * 2. clamp f_i and set r_ijk to predicted, decode r_ijk
	 */
	public FNParse decode(FgModel model, FgInferencerFactory infFactory) {
		
		final int n = sentence.size();
		

		// =============== DECODE FRAMES ===============================================================
		setupRoleVarsForDecode(VarType.LATENT);
		
		Frame[] decodedFrames = new Frame[n];
		VarConfig clampedFrames = new VarConfig();
		
		FgExample fge1 = this.getFgExample();
		FactorGraph fg1 = fge1.updateFgLatPred(model, params.logDomain);
		FgInferencer inf1 = infFactory.getInferencer(fg1);
		inf1.run();
		int numFramesTriggered = 0;
		for(int i=0; i<n; i++) {
			FrameVar fv = frameVars[i];
			if(fv == null) continue;
			
			DenseFactor df = inf1.getMarginals(fv.getFrameVar());
			int nullFrameIdx = 0;
			assert fv.getFrame(nullFrameIdx) == Frame.nullFrame;
			int f_dec_idx = params.frameDecoder.decode(df.getValues(), nullFrameIdx);

			if(debugDecodePart1 && params.debug)
				System.out.println("margins at " + i + " = " + df);

			decodedFrames[i] = fv.getFrame(f_dec_idx);
			clampedFrames.put(fv.getFrameVar(), f_dec_idx);
			
			if(decodedFrames[i] != Frame.nullFrame)
				numFramesTriggered++;
		}


		// =============== DECODE ARGS =================================================================
		FrameInstance[] decodedFIs = new FrameInstance[n];
		if(params.onlyFrameIdent) {
			for(int i=0; i<n; i++) {
				Frame f = decodedFrames[i];
				if(f == null || f == Frame.nullFrame)
					continue;
				Span[] args = new Span[f.numRoles()];
				Arrays.fill(args, Span.nullSpan);
				if(f != null && f != Frame.nullFrame)
					decodedFIs[i] = FrameInstance.newFrameInstance(f, Span.widthOne(i), args, sentence);
					//decodedFIs[i] = FrameInstance.frameMention(f, Span.widthOne(i), sentence);
			}
		}
		else if(numFramesTriggered > 0) {
			setupRoleVarsForRoleDecode(decodedFrames);

			FgExample needToModify = this.getFgExample();
			FactorGraph fg = needToModify.getOriginalFactorGraph().getClamped(clampedFrames);
			FgExample fge2 = new FgExample(fg, needToModify.getGoldConfig());
			fge2.updateFgLatPred(model, params.logDomain);
			FgInferencer inf2 = infFactory.getInferencer(fge2.getOriginalFactorGraph());
			inf2.run();
			
			for(int i=0; i<n; i++) {
				Frame f = decodedFrames[i];
				if(f == null || f == Frame.nullFrame)
					continue;
			
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
						DenseFactor df = inf2.getMarginals(r_ijk.getRoleVar());
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
							System.out.printf("[decode part2] %s.%s = %s risks:%s\n",
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
						DenseFactor df = inf2.getMarginals(r_ijk.getExpansionVar());
						if(debugDecodePart2 && params.debug) {
							System.out.println("[decode part2] expansion marginals: " + Arrays.toString(df.getValues()));
						}
						int expansionConfig = df.getArgmaxConfigId();
						args[k] = r_ijk.getSpan(expansionConfig);
					}
				}
				decodedFIs[i] = FrameInstance.newFrameInstance(f, Span.widthOne(i), args, sentence);
			}
		}
		
		List<FrameInstance> fis = new ArrayList<FrameInstance>();
		for(int i=0; i<n; i++)
			if(decodedFIs[i] != null)
				fis.add(decodedFIs[i]);
		return new FNParse(sentence, fis);
	}
	
	
	/**
	 * Returns an array with the indices corresponding to tokens in the sentence,
	 * and values are what FrameInstance has a target headed at the corresponding
	 * index. While in general FrameInstance's targets are Spans, we choose a head
	 * token to represent it for things like indexing i in f_i and r_ijk. 
	 */
	private FrameInstance[] setGoldForFrameVars(FNParse p) {
		
		if(p.getSentence() != sentence)
			throw new IllegalArgumentException();
		
		if(frameFilterStrat != FrameFilteringStrategy.USE_NULLFRAME_FOR_FILTERING_MISTAKES)
			throw new UnsupportedOperationException("implement me");
		
		// set all the labels to nullFrame/nullSpan, and then overwrite those that aren't null
		int n = p.getSentence().size();
		for(int i=0; i<n; i++) {
			if(frameVars[i] == null)
				continue;
			FrameInstance fiNull = FrameVar.nullFrameInstance(sentence, i);
			frameVars[i].setGold(fiNull);
		}

		// set the non-nullFrame vars to their correct Frame (and target Span) values
		FrameInstance[] locationsOfGoldFIs = new FrameInstance[n];	// return this information
		for(FrameInstance fi : p.getFrameInstances()) {
			int head = hf.head(fi.getTarget(), p.getSentence());
			locationsOfGoldFIs[head] = fi;
			if(frameVars[head] == null) {
				if(params.debug) {
					System.err.println("[setGold] invoking " + FrameFilteringStrategy.USE_NULLFRAME_FOR_FILTERING_MISTAKES +
							" because the candidate set of frames for " + sentence.getLU(head) + " did not include the gold frame: " + fi.getFrame());
				}
				continue;
			}
			frameVars[head].setGold(fi);
		}
		return locationsOfGoldFIs;
	}
	
	
	/**
	 * based on our target extraction and possible frame triage,
	 * what is the best recall we could hope to get?
	 */
	public double computeMaxTargetRecall(FNParse p) {
		final boolean debug = true;
		int reachable = 0, total = 0;
		for(FrameInstance fi : p.getFrameInstances()) {
			if(couldRecallTarget(fi)) reachable++;
			else if(debug) {
				System.err.printf("[ParsingSentence computeMaxTargetRecall] could not "
						+ "recover the frame %s listed in %s\n", fi.getFrame(), p.getSentence());
			}
			total++;
		}
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
		final int maxPrototypesPerFrame = 30;
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
				if(c > maxPrototypesPerFrame) continue;	// TODO reservoir sample
				if(uniqFrames.add(f)) {
					frameMatches.add(f);
					prototypes.add(fi);
				}
			}
		}
		
		// get frames that list this as an LU
		//LexicalUnit fnLU = s.getFNStyleLU(headIdx, params.targetPruningData.getWordnetDict());
		List<Frame> listedAsLUs = params.targetPruningData.getFramesFromLU(s.getLU(headIdx));
		for(Frame f : listedAsLUs) {
			if(uniqFrames.add(f)) {
				frameMatches.add(f);
				//prototypes.add(???);
			}
		}
		
		if(frameMatches.size() == 1)	// nullFrame
			return null;
		
		if(debugTargetRecall) {
			System.out.printf("[ParsingSentence makeFrameVar] trigger=%s frames=%s\n", s.getLU(headIdx), frameMatches);
			System.out.printf("[ParsingSentence makeFrameVar] trigger=%s prototypes=%s\n", s.getLU(headIdx), prototypes);
		}
		
		return new FrameVar(s, headIdx, prototypes, frameMatches, params);
	}
	
	
	public FactorGraph getFactorGraph() { return fg; }
	
	
	public VarConfig getGoldLabels() {
		if(gold.size() == 0)
			throw new IllegalStateException();
		return gold;
	}
	
	
	public FgExample getFgExample() {
		
		int n = this.sentence.size();
		this.fg = new FactorGraph();
		this.gold = new VarConfig();
		
		initFactors();
		
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
				for(int j=0; j<n; j++)
					for(int k=0; k<roleVars[i][j].length; k++)
						roleVars[i][j][k].register(fg, gold);
			}
		}

		for(Factor f : factors)
			fg.addFactor(f);

		return new FgExample(fg, gold);
	}
	
}

