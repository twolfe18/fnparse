package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.*;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.decode.MbrDecoder;
import edu.jhu.gm.decode.MbrDecoder.MbrDecoderPrm;
import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.model.FactorGraph.FgEdge;
import edu.jhu.gm.model.FactorGraph.FgNode;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.inference.heads.*;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;
import edu.jhu.hlt.fnparse.util.Counts;
import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.morph.WordnetStemmer;

public class ParsingSentence {
	
	//private static final Logger log = Logger.getLogger(ParsingSentence.class);

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
	
	private static final boolean debugDecodePart1 = false;	// frame decode
	private static final boolean debugDecodePart2 = true;	// arg decode
	
	public static final int maxLexPrototypesPerFrame = 30;
	
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
	
	// we want a factor that will connect frame heads with arg heads
	
	
	public ParsingSentence(Sentence s, ParserParams params) {
		
		this.params = params;
		this.factorHolders = params.factors;
		this.sentence = s;
		this.frameFilterStrat = FrameFilteringStrategy.USE_NULLFRAME_FOR_FILTERING_MISTAKES;
		
		int n = s.size();
		
		frameVars = new FrameVar[n];
		for(int i=0; i<n; i++)
			frameVars[i] = makeFrameVar(s, i, params.logDomain);
		
		if(!params.onlyFrameIdent) {
			roleVars = new RoleVars[n][][];
			for(int i=0; i<n; i++) {
				if(frameVars[i] == null)
					continue;
				int maxRoles = frameVars[i].getMaxRoles();
				roleVars[i] = new RoleVars[n][maxRoles];
				for(int j=0; j<n; j++)
					for(int k=0; k<maxRoles; k++)
						roleVars[i][j][k] = new RoleVars(frameVars[i], s, j, k, params.logDomain);	
			}

			if(params.useLatentDepenencies)
				depTree = new ProjDepTreeFactor(n, VarType.LATENT);
		}
		
		// initialize all the factors
		factors = new ArrayList<Factor>();
		if(params.useLatentDepenencies)
			factors.add(depTree);
		for(FactorFactory ff : factorHolders)
			factors.addAll(ff.initFactorsFor(sentence, frameVars, roleVars, depTree));
	}
	
	/**
	 * MBR decode may not be self-consistent...
	 * 
	 * here is how we should do it:
	 * 1. frames = \arg\max p(frames | x) = \sum_{args} p(frame, args | x)
	 * 2. args = \arg\max p(args | frames, x)
	 */
	public FNParse decode(FgModel model, FgInferencerFactory infFactory) {
		
		// first decode to find the best frames (marginalizing out role vars)
		MbrDecoderPrm prm = new MbrDecoderPrm();
		prm.infFactory = infFactory;
		MbrDecoder decoder = new MbrDecoder(prm);
		FgExample fge1 = this.getFgExample();
		BeliefPropagation bp = (BeliefPropagation) decoder.decode(model, fge1);
		VarConfig mbr1Conf = decoder.getMbrVarConfig();
		Map<Var, DenseFactor> margins1 = decoder.getVarMarginalsIndexed();
		
		List<Integer> dFrameIdx = new ArrayList<Integer>();
		List<Frame> dFrame = new ArrayList<Frame>();
		
		VarConfig clampedFrames = new VarConfig();
		int n = frameVars.length;
		for(int i=0; i<n; i++) {
			FrameVar fv = frameVars[i];
			if(fv == null) continue;
			
			if(debugDecodePart1) {
				DenseFactor localMargins = margins1.get(fv.getFrameVar());
				System.out.println(sentence.getLU(i) + "\t" + localMargins);
				
				FgNode fvNode = fg.getNode(fv.getFrameVar());
				for(FgEdge e : fvNode.getInEdges()) {
					System.out.println("edge    = " + e);
					System.out.println("factor  = " + e.getFactor());
					System.out.println("message = " + bp.getMessages()[e.getId()].message);
					//System.out.println(margins1.get(e.getChild().getVar()));
				}
				System.out.println();
				for(Frame f : fv.getFrames())
					System.out.printf("%s has %d args\n", f.getName(), f.numRoles());
				System.out.println();
			}
			
			clampedFrames.put(fv.getFrameVar(), mbr1Conf.getState(fv.getFrameVar()));
			clampedFrames.put(fv.getPrototypeVar(), mbr1Conf.getState(fv.getPrototypeVar()));	// shouldn't matter
			
			dFrameIdx.add(i);
			dFrame.add(fv.getFrame(mbr1Conf));
		}
		
		// decode with frames clamped
		FactorGraph fgWithClampedFrames = fge1.getOriginalFactorGraph().getClamped(clampedFrames);
		FgExample fge2 = new FgExample(fgWithClampedFrames, fge1.getGoldConfig());
		decoder.decode(model, fge2);
		VarConfig mbr2Conf = decoder.getMbrVarConfig();
		Map<Var, DenseFactor> margins2 = decoder.getVarMarginalsIndexed();
		List<FrameInstance> frameInstances = new ArrayList<FrameInstance>();
		for(int ii=0; ii<dFrameIdx.size(); ii++) {
			int i = dFrameIdx.get(ii);
			Frame f_i = dFrame.get(ii);
			
			if(f_i == Frame.nullFrame)
				continue;
			
			int K = f_i.numRoles();
			Span[] args = new Span[K];
			Arrays.fill(args, Span.nullSpan);
			if(!params.onlyFrameIdent) {
				for(int k=0; k<K; k++) {	// find the best span for every role
					Span bestSpan = Span.nullSpan;
					double bestSpanScore = -9999d;
					for(int j=0; j<n; j++) {
						RoleVars rv = roleVars[i][j][k];
						DenseFactor mR = margins2.get(rv.getRoleVar());
						if(mR == null) continue;	// we might be skipping arg inference
						double score = mR.getValue(mR.getArgmaxConfigId());

						//					DenseFactor mE = margins2.get(rv.getExpansionVar());
						//					assert mR.getValues().length == 2;
						//					assert mE.getValues().length == rv.getNumExpansions();
						//					double mRv = mR.getValue(BinaryVarUtil.boolToConfig(true));
						//					double mEv = mE.getValue(mE.getArgmaxConfigId());	// TODO this is wrong: should really clamp r_ijk before doing this.
						//					double score = params.logDomain ? mRv + mEv : mRv * mEv;

						if(score > bestSpanScore || bestSpan == null) {
							bestSpan = rv.getSpan(mbr2Conf);
							bestSpanScore = score;
						}

						if(debugDecodePart2)
							System.out.printf("[decode] f_i=%s  j=%s  k=%s  p(r_ijk)=%.3f\n",
									f_i.getName(), sentence.getWord(j), f_i.getRole(k), score);
					}
					if(bestSpan != null)
						args[k] = bestSpan;
				}
			}
			frameInstances.add(FrameInstance.newFrameInstance(f_i, Span.widthOne(i), args, sentence));
		}
		
		return new FNParse(sentence, frameInstances);
	}
	
	public void setGold(FNParse p) {
		
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
			
			if(roleVars != null) {
				for(int j=0; j<n; j++) {
					int K = roleVars[i][j].length; 
					for(int k=0; k<K; k++)
						roleVars[i][j][k].setGoldIsNull();
				}
			}
		}

		// set the non-nullFrame vars to their correct Frame (and target Span) values
		HeadFinder hf = new BraindeadHeadFinder();	// TODO
		for(FrameInstance fi : p.getFrameInstances()) {
			
			int head = hf.head(fi.getTarget(), p.getSentence());
			if(frameVars[head] == null) {
				System.err.println("[setGold] invoking " + FrameFilteringStrategy.USE_NULLFRAME_FOR_FILTERING_MISTAKES +
						" because the candidate set of frames for " + sentence.getLU(head) + " did not include the gold frame: " + fi.getFrame());
				continue;
			}
			frameVars[head].setGold(fi);
			
			// set role variables that were instantiated
			if(roleVars != null) {
				int i = head;
				int K = fi.getFrame().numRoles();
				for(int k=0; k<K; k++) {
					Span argSpan = fi.getArgument(k);
					if(argSpan == Span.nullSpan)
						continue;	// we've already set the relevant vars to nullSpan
					int j = hf.head(argSpan, sentence);
					if(k < roleVars[i][j].length)
						roleVars[i][j][k].setGold(argSpan);
					else {
						System.err.printf("we thought there was a max of %d roles for frames evoked by %s, but the true frame %s has %d roles\n",
								roleVars[i][j].length, sentence.getLU(i), fi.getFrame(), fi.getFrame().numRoles());
					}
				}
			}
		}
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
		
		// OLDER
//		List<Frame> matchesByLU = params.targetPruningData.getFramesFromLU(head);
//		List<FrameInstance> matchesByWord = params.targetPruningData.getFrameInstanceForWord(head.word);
//		for(Frame f : matchesByLU) {
//			if(uniqFrames.add(f)) {
//				List<FrameInstance> ps = params.prototypes.get(f);	// THIS ALSO COMES FROM LEX!
//				if(ps != null) {
//					if(ps.size() > maxLexPrototypesPerFrame)
//						ps = DataUtil.reservoirSample(ps, maxLexPrototypesPerFrame);
//					prototypes.addAll(ps);
//				}
//				if(prototypes.size() > 500) {
//					System.err.println("poop1");
//				}
//				frameMatches.add(f);
//			}
//		}
//		for(FrameInstance fi : matchesByWord) {	// from lex examples
//			Frame f = fi.getFrame();
//			if(uniqFrames.add(f))
//				frameMatches.add(f);
//			//prototypes.add(fi);
//		}
//		if(prototypes.size() > 500) {
//			System.err.println("poop2");
//		}
		
		
		// OLDEST
//		for(Frame f : params.frameIndex.allFrames()) {
//			// check if this matches a lexical unit for this frame
//			for(int i = 0; i < f.numLexicalUnits(); i++) {
//				if(LexicalUnit.approxMatch(head, f.getLexicalUnit(i))) {
//					frameMatches.add(f);
//					List<FrameInstance> ps = params.prototypes.get(f); 
//					if(ps != null) prototypes.addAll(ps);
//					break;
//				}
//			}
//		}
		
		if(frameMatches.size() == 1) {
			//System.err.println("[makeFrameVars] WARNING: no frames available for " + s.getLU(headIdx));
			return null;
		}
		
		System.out.printf("[ParsingSentence makeFrameVar] trigger=%s frames=%s\n", s.getLU(headIdx), frameMatches);
		System.out.printf("[ParsingSentence makeFrameVar] trigger=%s prototypes=%s\n", s.getLU(headIdx), prototypes);
		
		return new FrameVar(s, headIdx, prototypes, frameMatches, logDomain);
	}
	
	public FactorGraph getFactorGraph() { return fg; }
	
	public VarConfig getGoldLabels() {
		if(gold.size() == 0)
			throw new RuntimeException();
		return gold;
	}
	
	public FgExample getFgExample() {
		
		int n = this.sentence.size();
		this.fg = new FactorGraph();
		this.gold = new VarConfig();
		
		// register all the variables and factors
//		int fvConfigs = 0;
		for(int i=0; i<n; i++) {
			if(frameVars[i] ==  null)
				continue;
			frameVars[i].register(fg, gold);
//			fvConfigs += frameVars[i].numberOfConfigs();
		}
		if(roleVars != null) {
			for(int i=0; i<n; i++) {
				if(frameVars[i] == null)
					continue;
				for(int j=0; j<n; j++)
					for(int k=0; k<roleVars[i][j].length; k++)
						roleVars[i][j][k].register(fg, gold);
			}
		}

		for(Factor f : factors)
			fg.addFactor(f);
		
//		System.out.printf("[ParsingSentence getFgExample] there are %d variables and %d factors "
//				+ "for a length %d sentence, returning example\n",
//				fg.getNumVars(), fg.getNumFactors(), this.sentence.size());
//		System.out.printf("[ParsingSentence getFgExample] sum(fv.numConfigs)=%d\n", fvConfigs);
		
		return new FgExample(fg, gold);
	}
	
	public List<Factor> getFactorsFromFactories() { return factors; }
}

