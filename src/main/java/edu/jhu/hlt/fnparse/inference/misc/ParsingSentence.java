package edu.jhu.hlt.fnparse.inference.misc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.data.UnlabeledFgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.PosUtil;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.frameid.FrameVars;
import edu.jhu.hlt.fnparse.inference.misc.Parser.ParserParams;
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
 * @param Hypothesis is the type that serves as a variable wrapper. This class must
 * be capable of storing a label, and the {@code setGold} method should change the
 * state of the {@code Hypothesis}s' labels.
 * 
 * @param Label is the type for whatever information you need to set the labels in
 * the factor graph.
 * 
 * @see {@code FrameIdSentence}, {@code RoleIdSentence}, {@code JointFrameRoleIdSentence}.
 * 
 * @author travis
 */
public abstract class ParsingSentence<Hypothesis extends FgRelated, Label> {
	
	protected final Label gold;	// must be set at construction

	// hyp could be FrameVars, RoleVars, or FrameInstanceHypothesis
	// should be populated at construction
	public List<Hypothesis> hypotheses;

	public ProjDepTreeFactor depTree;	// holds variables too
	
	protected FactorFactory<Hypothesis> factorTemplate;
	
	public Sentence sentence;
	protected ParserParams params;
	

	/** constructor for cases where you don't know the label */
	public ParsingSentence(Sentence s, ParserParams params, FactorFactory<Hypothesis> factorTemplate) {
		this(s, params, factorTemplate, null);
	}

	/** constructor for cases where you do know the label */
	public ParsingSentence(Sentence s, ParserParams params, FactorFactory<Hypothesis> factorTemplate, Label label) {
		this.params = params;
		this.factorTemplate = factorTemplate;
		this.sentence = s;
		this.hypotheses = new ArrayList<Hypothesis>();
		this.gold = label;
	}
	
	

	/** you should use this in your implementation of decode() */
	protected FgExample getExample(boolean labeled) {
		
		FactorGraph fg = new FactorGraph();
		VarConfig gold = new VarConfig();
		
		// create factors
		List<Factor> factors = new ArrayList<Factor>();
		if(params.useLatentDepenencies) {
			assert depTree != null;
			factors.add(depTree);
		}
		factors.addAll(factorTemplate.initFactorsFor(sentence, hypotheses, depTree));
		
		// register all the variables and factors
		for(Hypothesis hyp : hypotheses)
			hyp.register(fg, gold);

		// add factors to the factor graph
		for(Factor f : factors)
			fg.addFactor(f);

		return labeled
			? new LabeledFgExample(fg, gold)
			: new UnlabeledFgExample(fg, gold);
	}

	/**
	 * This method should zoom down to the variables held in the hypotheses and set the gold value.
	 * After this call, calling hyp.register(fg, goldConf) should actually add to goldConf.
	 */
	protected abstract void setGold(Label gold);

	
	/** might return a FNParse depending on the settings */
	// NOTE: you might want to return a FNTaggin (e.g. if you're only doing frameId), but the types are more annoying than they're worth: everything's a FNParse now
	public abstract FNParse decode(FgModel model, FgInferencerFactory infFactory);
	
	public LabeledFgExample getTrainingExample() {
		if(gold == null)
			throw new RuntimeException();
		setGold(gold);
		return (LabeledFgExample) getExample(true);
	}

	

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

		final int maxLexPrototypesPerFrame = 30;
		
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
	
}

