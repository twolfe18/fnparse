package edu.jhu.hlt.fnparse.inference.stages;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.gm.train.CrfTrainer.CrfTrainerPrm;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.PosUtil;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.features.BinaryBinaryFactorHelper;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.dep.DepParseFactorFactory;
import edu.jhu.hlt.fnparse.inference.frameid.FrameFactorFactory;
import edu.jhu.hlt.fnparse.inference.frameid.FrameVars;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.hlt.optimize.AdaGrad;
import edu.jhu.hlt.optimize.AdaGrad.AdaGradPrm;
import edu.jhu.hlt.optimize.SGD;
import edu.jhu.hlt.optimize.SGD.SGDPrm;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.L2;
import edu.jhu.util.Alphabet;
import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.morph.WordnetStemmer;

public class FrameIdStage extends AbstractStage<Sentence, FNTagging> implements Stage<Sentence, FNTagging> {
	
	public static class Params implements Serializable {
		private static final long serialVersionUID = 1L;
		public int threads = 2;
		public int batchSize = 4;
		public int passes = 2;
		public double propDev = 0.15d;
		public int maxDev = 50;
		public Regularizer regularizer = new L2(10000d);
		public Double learningRate = null;	// if null, auto select learning rate
		public ApproxF1MbrDecoder decoder;
		public TargetPruningData targetPruningData;
		public FactorFactory<FrameVars> factorsTemplate;
		public ParserParams globalParams;
	}
	
	public Params params = new Params();
	
	public FrameIdStage(ParserParams globalParams) {
		super(globalParams);
		params.globalParams = globalParams;
		params.decoder = new ApproxF1MbrDecoder(globalParams.logDomain, 2.5);
		params.targetPruningData = TargetPruningData.getInstance();
		BinaryBinaryFactorHelper.Mode factorMode = globalParams.useLatentDepenencies
				? BinaryBinaryFactorHelper.Mode.ISING : BinaryBinaryFactorHelper.Mode.NONE;
		params.factorsTemplate = new FrameFactorFactory(globalParams, factorMode);
	}
	
	public void train(List<FNTagging> examples) {
		Collections.shuffle(examples, globalParams.rand);
		List<Sentence> x = new ArrayList<>();
		List<FNTagging> y = new ArrayList<>();
		for(FNTagging t : examples) {
			x.add(t.getSentence());
			y.add(t);
		}
		train(x, y);
	}

	@Override
	public void train(List<? extends Sentence> x, List<? extends FNTagging> y) {
		
		if(globalParams.featIdx.size() == 0)
			throw new IllegalArgumentException("run AlphabetComputer first!");
		assert globalParams.verifyConsistency();
		Timer t = globalParams.timer.get("frameId-train", true);
		t.start();
		
		List<Sentence> xTrain = new ArrayList<>();
		List<FNTagging> yTrain = new ArrayList<>();
		List<Sentence> xDev = new ArrayList<>();
		List<FNTagging> yDev = new ArrayList<>();
		devTuneSplit(x, y, xTrain, yTrain, xDev, yDev, 0.15d, 50, globalParams.rand);
		
		CrfTrainerPrm trainerParams = new CrfTrainerPrm();
		SGDPrm sgdParams = new SGDPrm();
		AdaGradPrm adagParams = new AdaGradPrm();
		if(params.learningRate == null)
			sgdParams.autoSelectLr = true;
		else {
			sgdParams.autoSelectLr = false;
			adagParams.eta = params.learningRate;
		}
		sgdParams.batchSize = params.batchSize;
		sgdParams.numPasses = params.passes;
		sgdParams.sched = new AdaGrad(adagParams);

		trainerParams.maximizer = null;
		trainerParams.batchMaximizer = new SGD(sgdParams);
		trainerParams.infFactory = infFactory();
		trainerParams.numThreads = params.threads;

		Alphabet<String> alph = params.globalParams.featIdx;
		System.out.printf("[FrameId train] alphabet is frozen (size=%d), going straight into training\n", alph.size());
		alph.stopGrowth();
		
		// get the data
		StageDatumExampleList<Sentence, FNTagging> exs = this.setupInference(xTrain, yTrain);
		
		// setup model and train
		CrfTrainer trainer = new CrfTrainer(trainerParams);
		try {
			params.globalParams.weights = trainer.train(params.globalParams.weights, exs);
		}
		catch(cc.mallet.optimize.OptimizationException oe) {
			oe.printStackTrace();
		}
		long timeTrain = t.stop();
		System.out.printf("[FrameId train] done training on %d examples for %.1f minutes\n", exs.size(), timeTrain/(1000d*60d));
		System.out.println("[FrameId train] params.featIdx.size = " + alph.size());

		// tune
		tune(xDev, yDev);
	}


	private void tune(List<Sentence> x, List<FNTagging> y) {
		Timer t = globalParams.timer.get("frameId-tune", true);
		t.start();
		List<Double> biases = new ArrayList<Double>();
		for(double b = 0.5d; b < 8d; b *= 1.1d) biases.add(b);
		StageDatumExampleList<Sentence, FNTagging> dec = setupInference(x);
		tuneRecallBias(dec, params.decoder, BasicEvaluation.targetMicroF1, biases);
		t.stop();
	}


	@Override
	public StageDatumExampleList<Sentence, FNTagging> setupInference(List<? extends Sentence> input) {
		return setupInference(input, null);
	}

	/**
	 * if labels is not null, will create FrameIdData with labels
	 */
	public StageDatumExampleList<Sentence, FNTagging> setupInference(List<? extends Sentence> input, List<? extends FNTagging> labels) {
		List<StageDatum<Sentence, FNTagging>> data = new ArrayList<>();
		int n = input.size();
		assert labels == null || labels.size() == n;
		for(int i=0; i<n; i++) {
			Sentence s = input.get(i);
			if(labels == null)
				data.add(new FrameIdDatum(s, params));
			else
				data.add(new FrameIdDatum(s, params, labels.get(i)));
		}
		return new StageDatumExampleList<Sentence, FNTagging>(data);
	}

	
	/**
	 * Given a word in a sentence, extract the set of frames it might evoke.
	 * Basic idea: given a target with head word t, include any frame f s.t.
	 * lemma(t) == lemma(f.target)
	 */
	public static FrameVars makeFrameVar(Sentence s, int headIdx, Params params) {
		
		if(params.targetPruningData.prune(headIdx, s))
			return null;

		Set<Frame> uniqFrames = new HashSet<Frame>();
		List<Frame> frameMatches = new ArrayList<Frame>();
		List<FrameInstance> prototypes = new ArrayList<FrameInstance>();
		
		// get prototypes/frames from the LEX examples
		Map<String, List<FrameInstance>> stem2prototypes = params.targetPruningData.getPrototypesByStem();
		IRAMDictionary wnDict = params.targetPruningData.getWordnetDict();
		WordnetStemmer stemmer = new WordnetStemmer(wnDict);
		String word = s.getWord(headIdx);
		POS pos = PosUtil.ptb2wordNet(s.getPos(headIdx));
		List<String> stems = null;
		try { stems = stemmer.findStems(word, pos); }
		catch(IllegalArgumentException e) { return null; }	// words that normalized to an empty string throw an exception
		for(String stem : stems) {
			List<FrameInstance> protos = stem2prototypes.get(stem);
			if(protos == null) continue;
			for(FrameInstance fi : protos) {
				Frame f = fi.getFrame();
				if(uniqFrames.add(f)) {
					frameMatches.add(f);
					prototypes.add(fi);
				}
			}
		}
		
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
		
//		if(globalParams.debug) {
//			int framesFromLexExamples = frameMatches.size();
//			System.out.printf("[ParsingSentence makeFrameVar] #frames-from-LEX=%d #frames-from-LUs=%d\n",
//					framesFromLexExamples, listedAsLUs.size());
//			System.out.printf("[ParsingSentence makeFrameVar] trigger=%s frames=%s\n",
//					s.getLU(headIdx), frameMatches);
//			System.out.printf("[ParsingSentence makeFrameVar] trigger=%s prototypes=%s\n",
//					s.getLU(headIdx), prototypes);
//		}
		
		return new FrameVars(headIdx, prototypes, frameMatches);
	}
	
	

		
	
	/**
	 * Takes a sentence, and optionally a FNTagging, and can make either
	 * {@link Decodable}<FNTagging>s (for prediction) or
	 * {@link LabeledFgExample}s for training.
	 * 
	 * @author travis
	 */
	public static class FrameIdDatum implements StageDatum<Sentence, FNTagging> {
		
		private final Params params;
		private final Sentence sentence;
		private final boolean hasGold;
		private final FNTagging gold;
		private final List<FrameVars> possibleFrames;
		
		public FrameIdDatum(Sentence s, Params params) {
			this.params = params;
			this.sentence = s;
			this.hasGold = false;
			this.gold = null;
			this.possibleFrames = new ArrayList<>();
			initHypotheses();
		}

		public FrameIdDatum(Sentence s, Params params, FNTagging gold) {
			assert gold != null;
			this.params = params;
			this.sentence = s;
			this.hasGold = true;
			this.gold = gold;
			this.possibleFrames = new ArrayList<>();
			initHypotheses();
			setGold(gold);
		}

		private void initHypotheses() {
			final int n = sentence.size();
			if(n < 4) {
				// TODO check more carefully, like 4 content words or has a verb
				System.err.println("[FrameIdSentence] skipping short sentence: " + sentence);
				return;
			}
			for(int i=0; i<n; i++) {
				FrameVars fv = makeFrameVar(sentence, i, params);
				if(fv != null) possibleFrames.add(fv);
			}
		}

		private void setGold(FNTagging p) {
			
			if(p.getSentence() != sentence)
				throw new IllegalArgumentException();
			
			// build an index from targetHeadIdx to FrameRoleVars
			Set<FrameVars> haventSet = new HashSet<FrameVars>();
			FrameVars[] byHead = new FrameVars[sentence.size()];
			for(FrameVars fHyp : this.possibleFrames) {
				assert byHead[fHyp.getTargetHeadIdx()] == null;
				byHead[fHyp.getTargetHeadIdx()] = fHyp;
				haventSet.add(fHyp);
			}
			
			// match up each FI to a FIHypothesis by the head word in the target
			for(FrameInstance fi : p.getFrameInstances()) {
				Span target = fi.getTarget();
				int head = params.globalParams.headFinder.head(target, sentence);
				FrameVars fHyp = byHead[head];
				if(fHyp == null) continue;	// nothing you can do here
				if(fHyp.goldIsSet()) {
					System.err.println("WARNING: " + p.getId() +
							" has at least two FrameInstances with the same target head word, choosing the first one");
					continue;
				}
				fHyp.setGold(fi);
				boolean removed = haventSet.remove(fHyp);
				assert removed : "two FrameInstances with same head? " + p.getSentence().getId();
			}
			
			// the remaining hypotheses must be null because they didn't correspond to a FI in the parse
			for(FrameVars fHyp : haventSet)
				fHyp.setGoldIsNull();
		}

		@Override
		public Sentence getInput() { return sentence; }

		@Override
		public boolean hasGold() { return hasGold; }

		@Override
		public LabeledFgExample getExample() {
			FactorGraph fg = getFactorGraph();
			VarConfig gold = new VarConfig();
			
			// add the gold labels
			for(FrameVars hyp : possibleFrames) {
				assert hyp.goldIsSet();
				hyp.register(fg, gold);
			}

			return new LabeledFgExample(fg, gold);
		}

		private FactorGraph getFactorGraph() {
			FactorGraph fg = new FactorGraph();
			
			// create factors
			List<Factor> factors = new ArrayList<Factor>();
			ProjDepTreeFactor depTree = null;
			if(params.globalParams.useLatentDepenencies) {
				depTree = new ProjDepTreeFactor(sentence.size(), VarType.LATENT);
				DepParseFactorFactory depParseFactorTemplate = new DepParseFactorFactory(params.globalParams);
				factors.addAll(depParseFactorTemplate.initFactorsFor(sentence, Collections.emptyList(), depTree));
			}
			factors.addAll(params.factorsTemplate.initFactorsFor(sentence, possibleFrames, depTree));

			// add factors to the factor graph
			for(Factor f : factors)
				fg.addFactor(f);
			
			return fg;
		}

		@Override
		public FrameIdDecodable getDecodable(FgInferencerFactory infFact) {
			FactorGraph fg = this.getFactorGraph();
			return new FrameIdDecodable(sentence, possibleFrames, fg, infFact, params);
		}

		@Override
		public FNTagging getGold() {
			assert hasGold();
			return gold;
		}

	}
	
	
	/**
	 * Stores beliefs about frameId variables (see {@link Decodable}) and implements the decoding step.
	 * 
	 * @author travis
	 */
	public static class FrameIdDecodable extends Decodable<FNTagging> implements Iterable<FrameVars> {

		private final Params params;
		private final Sentence sentence;
		private final List<FrameVars> possibleFrames;

		public FrameIdDecodable(Sentence sent, List<FrameVars> possibleFrames,
				FactorGraph fg, FgInferencerFactory infFact, Params params) {
			super(fg, infFact);
			this.params = params;
			this.sentence = sent;
			this.possibleFrames = possibleFrames;
		}

		public Sentence getSentence() { return sentence; }

		@Override
		public Iterator<FrameVars> iterator() { return possibleFrames.iterator(); }

		@Override
		public FNTagging decode() {
			FgInferencer hasMargins = this.getMargins();
			List<FrameInstance> fis = new ArrayList<FrameInstance>();
			for(FrameVars fvars : possibleFrames) {
				final int T = fvars.numFrames();
				double[] beliefs = new double[T];
				for(int t=0; t<T; t++) {
					DenseFactor df = hasMargins.getMarginals(fvars.getVariable(t));
					beliefs[t] = df.getValue(BinaryVarUtil.boolToConfig(true));
				}
				params.globalParams.normalize(beliefs);

				final int nullFrameIdx = fvars.getNullFrameIdx();
				int tHat = params.decoder.decode(beliefs, nullFrameIdx);
				Frame fHat = fvars.getFrame(tHat);
				if(fHat != Frame.nullFrame)
					fis.add(FrameInstance.frameMention(fHat, Span.widthOne(fvars.getTargetHeadIdx()), sentence));
			}
			return new FNParse(sentence, fis);
		}
	}
	
}
