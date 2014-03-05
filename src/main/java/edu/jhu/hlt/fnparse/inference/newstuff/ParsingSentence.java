package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.*;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.decode.MbrDecoder;
import edu.jhu.gm.decode.MbrDecoder.MbrDecoderPrm;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
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

//	private static final boolean debugDecodePart1 = false;	// frame decode
//	private static final boolean debugDecodePart2 = true;	// arg decode
	
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
			assert (goldFIat_i == null) == (fi.getGoldFrame() == Frame.nullFrame);
			
			int K = fi.getMaxRoles();
			roleVars[i] = new RoleVars[n][K];
			for(int j=0; j<n; j++) {
				for(int k=0; k<K; k++) {
			
					// mark's point: make stuff that doesn't matter latent
					VarType r_ijkType = k >= fi.getGoldFrame().numRoles() || fi.getGoldFrame() == Frame.nullFrame
							? VarType.LATENT
							: VarType.PREDICTED;
					
					RoleVars rv = new RoleVars(r_ijkType, fi.getFrames(), sentence, i, j, k, params.logDomain);
					roleVars[i][j][k] = rv;
					
					if(r_ijkType == VarType.PREDICTED) {	// set gold
						Frame f = goldFIat_i.getFrame();
						Span arg = goldFIat_i.getArgument(k);
						if(arg == Span.nullSpan)
							rv.setGoldIsNull();
						else
							rv.setGold(f, arg);
					}
				}
			}
		}
	}
	
	
	/**
	 * all r_ijk are instantiated as latent with arity of \max_f f.numRoles
	 */
	public void setupRoleVarsForFrameDecode() {
		assert frameVars != null;
		final int n = sentence.size();
		roleVars = new RoleVars[n][][];
		for(int i=0; i<n; i++) {
			FrameVar fv = frameVars[i];
			if(fv == null) continue;
			final int K = fv.getMaxRoles();
			roleVars[i] = new RoleVars[n][K];
			for(int j=0; j<n; j++)
				for(int k=0; k<K; k++)
					roleVars[i][j][k] = new RoleVars(VarType.LATENT, fv.getFrames(), sentence, i, j, k, params.logDomain);
		}
	}

	
	/**
	 * prune r_ijk corresponding to f_i == nullFrame,
 	 * the other r_ijk will be predicted and binary
	 */
	public void setupRoleVarsForRoleDecode(Frame[] decodedFrames) {
		assert frameVars != null;
		final int n = sentence.size();
		roleVars = new RoleVars[n][][];
		for(int i=0; i<n; i++) {
			FrameVar fv = frameVars[i];
			Frame f = decodedFrames[i];
			if(fv == null || f == Frame.nullFrame)
				continue;
			List<Frame> possibleFrames = Arrays.asList(Frame.nullFrame, f);
			int K = f.numRoles();
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
		
		// decode frames
		setupRoleVarsForFrameDecode();
		
		// TODO need to modify the MBR decoder so that I can penalize
		// false negatives and fall positives differently so that I can maximize F1
		MbrDecoderPrm prm = new MbrDecoderPrm();
		prm.infFactory = infFactory;
		MbrDecoder decoder = new MbrDecoder(prm);
		FgExample fge1 = this.getFgExample();
		decoder.decode(model, fge1);
		VarConfig f_i_decode = decoder.getMbrVarConfig();
		Frame[] decodedFrames = new Frame[n];
		for(int i=0; i<n; i++) {
			FrameVar fv = frameVars[i];
			if(fv == null) continue;
			decodedFrames[i] = fv.getFrame(f_i_decode);
		}

		// decode args
		FrameInstance[] decodedFIs = new FrameInstance[n];
		if(params.onlyFrameIdent) {
			for(int i=0; i<n; i++) {
				Frame f = decodedFrames[i];
				if(f != null)
					decodedFIs[i] = FrameInstance.frameMention(f, Span.widthOne(i), sentence);
			}
		}
		else {
			setupRoleVarsForRoleDecode(decodedFrames);

			// here we don't necessarily have the gaurantee that we won't
			// see and argument realized twice (TODO check on the Exactly1 factor),
			// so we'll get the margins for r_ijk and take the max over k for each i.
			FgExample fge2 = this.getFgExample();
			decoder.decode(model, fge2);
			Map<Var, DenseFactor> margins2 = decoder.getVarMarginalsIndexed();
			for(int i=0; i<n; i++) {
				Frame f = decodedFrames[i];
				if(f == null || f == Frame.nullFrame)
					continue;
				Span[] args = new Span[f.numRoles()];
				Arrays.fill(args, Span.nullSpan);
				for(int k=0; k<f.numRoles(); k++) {
					// max over j
					ArgMax<Integer> bestHead = new ArgMax<Integer>();
					for(int j=0; j<n; j++) {
						RoleVars r_ijk = roleVars[i][j][k];
						int indexOfRealizedArg = r_ijk.getPossibleFrames().indexOf(f);
						double headScore = margins2.get(r_ijk.getRoleVar()).getValue(indexOfRealizedArg);
						bestHead.accum(j, headScore);
					}
					int j = bestHead.getBest();
					RoleVars r_ijk = roleVars[i][j][k];
					args[k] = r_ijk.getSpanDummy();
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
		HeadFinder hf = new BraindeadHeadFinder();	// TODO
		for(FrameInstance fi : p.getFrameInstances()) {
			int head = hf.head(fi.getTarget(), p.getSentence());
			locationsOfGoldFIs[head] = fi;
			if(frameVars[head] == null) {
				System.err.println("[setGold] invoking " + FrameFilteringStrategy.USE_NULLFRAME_FOR_FILTERING_MISTAKES +
						" because the candidate set of frames for " + sentence.getLU(head) + " did not include the gold frame: " + fi.getFrame());
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
		
		if(s.getWord(headIdx).equals("later")) {
			System.out.println("debugging");
		}
		
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
		
//		System.out.printf("[ParsingSentence makeFrameVar] trigger=%s frames=%s\n", s.getLU(headIdx), frameMatches);
//		System.out.printf("[ParsingSentence makeFrameVar] trigger=%s prototypes=%s\n", s.getLU(headIdx), prototypes);
		
		return new FrameVar(s, headIdx, prototypes, frameMatches, logDomain);
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

