package edu.jhu.hlt.fnparse.inference.sentence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.ProjDepTreeFactor;
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

	/* like a regular FgExample, but stores a backpointer to the ParsingSentence it came from
	public static class FgExample extends edu.jhu.gm.data.LabeledFgExample {
		private static final long serialVersionUID = 1L;
		public final ParsingSentence cameFrom;
		public FgExample(FactorGraph fg, VarConfig goldConfig, ParsingSentence cameFrome) {
			super(fg, goldConfig);
			this.cameFrom = cameFrome;
		}
	}
	*/
	
	
	// move this into ParserParams?
	public static final int maxLexPrototypesPerFrame = 30;
	

	// ==== VARIABLES ====
	public List<FrameInstanceHypothesis> frameRoleVars;
	public ProjDepTreeFactor depTree;	// holds variables too
	
	// ==== FACTORS ====
	protected List<FactorFactory> factorTemplates;
	
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
			boolean removed = haventSet.remove(fHyp);
			assert removed : "two FrameInstances with same head?";
		}
		
		// the remaining hypotheses must be null because they didn't correspond to a FI in the parse
		for(FrameInstanceHypothesis fHyp : haventSet)
			fHyp.setGoldIsNull();
	}

	
	/** might return a FNParse depending on the settings */
	// NOTE: you might want to return a FNTaggin (e.g. if you're only doing frameId), but the types are more annoying than they're worth: everything's a FNParse now
	public abstract FNParse decode(FgModel model, FgInferencerFactory infFactory);
	
	public abstract LabeledFgExample getTrainingExample();

	

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
	protected FrameVars makeFrameVar(Sentence s, int headIdx, boolean logDomain) {
		
		if(params.targetPruningData.prune(headIdx, s))
			return null;

		Set<Frame> uniqFrames = new HashSet<Frame>();
		List<Frame> frameMatches = new ArrayList<Frame>();
		List<FrameInstance> prototypes = new ArrayList<FrameInstance>();
		
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
		
		if(frameMatches.size() == 0)
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

