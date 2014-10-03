package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
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
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.BinaryBinaryFactorHelper;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.dep.DepParseFactorFactory;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.L2;

public class FrameIdStage
		extends AbstractStage<Sentence, FNTagging>
		implements Stage<Sentence, FNTagging>, Serializable {

	private static final long serialVersionUID = 1L;
	public static final Logger LOG = Logger.getLogger(FrameIdStage.class);

	public static class Params implements Serializable {
		private static final long serialVersionUID = 1L;

		// If null, auto select learning rate
		public Double learningRate = 0.05d;
		public int batchSize = 4;
		public int passes = 2;
		public double propDev = 0.15d;
		public int maxDev = 50;
		public transient Regularizer regularizer = new L2(1_000_000d);
		public ApproxF1MbrDecoder decoder;
		public Features.F features;
		public FactorFactory<FrameVars> factorsTemplate;
		//public final ParserParams globalParams;

		public TargetPruningData getTargetPruningData() {
			return TargetPruningData.getInstance();
		}

		// If true, tuning the decoder will be done on training data. Generally
		// this is undesirable for risk of overfitting, but is good for
		// debugging (e.g. an overfitting test).
		public boolean tuneOnTrainingData = false;

		// If true, will add all frame to the set to be predicted from even if
		// it has a different part of speech tag as a listed lexical unit
		// (e.g. "terrorist.JJ" will match the Terrorism  frame which lists
		// "terrorist.n" but not "terrorist.a". The downside of setting this to
		// false is that you will add some false positives.
		public boolean useJustWordForPossibleFrames = true;

		// If true, try lowercasing the target word before looking it up in the
		// frame index.
		public boolean lowercaseWordForPossibleFrames = true;

		// If true, remove the "s" from words ending in "es" while looking up
		// frames listed by lexical unit. E.g. "doses.NNS" => "dose.n"
		public boolean useHackySingularSingularConversion = true;

		public Params(ParserParams globalParams) {
			decoder = new ApproxF1MbrDecoder(globalParams.logDomain, 2.5d);
			//getTargetPruningData() = TargetPruningData.getInstance();
			BinaryBinaryFactorHelper.Mode factorMode =
					globalParams.useLatentDepenencies
						? BinaryBinaryFactorHelper.Mode.ISING
						: BinaryBinaryFactorHelper.Mode.NONE;
			features = new BasicFrameFeatures(globalParams);
			factorsTemplate = new FrameFactorFactory(features, factorMode);
		}
	}

	public Params params;

	public FrameIdStage(
			ParserParams globalParams,
			HasFeatureAlphabet featureAlphabet) {
		super(globalParams, featureAlphabet);
		params = new Params(globalParams);
		((FrameFactorFactory) params.factorsTemplate)
			.setAlphabet(globalParams.getAlphabet());
	}

	@Override
	public Serializable getParamters() {
		return params;
	}

	@Override
	public void setPameters(Serializable params) {
		this.params = (Params) params;
	}

	@Override
	public Double getLearningRate() {
		return params.learningRate;
	}

	@Override
	public Regularizer getRegularizer() {
		return params.regularizer;
	}

	@Override
	public int getBatchSize() {
		return params.batchSize;
	}

	@Override
	public int getNumTrainingPasses() {
		return params.passes;
	}

	public void train(List<FNParse> examples) {
		Collections.shuffle(examples, globalParams.rand);
		List<Sentence> x = new ArrayList<>();
		List<FNTagging> y = new ArrayList<>();
		for (FNTagging t : examples) {
			x.add(t.getSentence());
			y.add(t);
		}
		train(x, y, params.learningRate,
				params.regularizer, params.batchSize, params.passes);
	}

	@Override
	public TuningData getTuningData() {
		final List<Double> biases = new ArrayList<Double>();
		for(double b=0.5d; b<8d; b *= 1.1d) biases.add(b);
		LOG.debug("called getTuningData, trying " + biases.size() + " biases");

		return new TuningData() {
			@Override
			public ApproxF1MbrDecoder getDecoder() { return params.decoder; }
			@Override
			public EvalFunc getObjective() { return BasicEvaluation.targetMicroF1; }
			@Override
			public List<Double> getRecallBiasesToSweep() { return biases; }
			@Override
			public boolean tuneOnTrainingData() { return params.tuneOnTrainingData; }
		};
	}

	@Override
	public StageDatumExampleList<Sentence, FNTagging> setupInference(
			List<? extends Sentence> input,
			List<? extends FNTagging> labels) {
		List<StageDatum<Sentence, FNTagging>> data = new ArrayList<>();
		int n = input.size();
		assert labels == null || labels.size() == n;
		for (int i = 0; i < n; i++) {
			Sentence s = input.get(i);
			if(labels == null)
				data.add(new FrameIdDatum(s, this));
			else
				data.add(new FrameIdDatum(s, this, labels.get(i)));
		}
		return new StageDatumExampleList<Sentence, FNTagging>(data);
	}

	/**
	 * Given a word in a sentence, extract the set of frames it might evoke.
	 * Basic idea: given a target with head word t, include any frame f s.t.
	 * lemma(t) == lemma(f.target)
	 */
	public static FrameVars makeFrameVar(
			Sentence s,
			int headIdx,
			Params params) {
		if(params.getTargetPruningData().prune(headIdx, s))
			return null;

		Set<Frame> uniqFrames = new HashSet<Frame>();
		List<Frame> frameMatches = new ArrayList<Frame>();
		List<FrameInstance> prototypes = new ArrayList<FrameInstance>();

		// Get prototypes/frames from the LEX examples
		List<FrameInstance> fromPrototypes =
				params.getTargetPruningData()
				.getPrototypesByStem(headIdx, s, true);
		for (FrameInstance fi : fromPrototypes) {
			if (uniqFrames.add(fi.getFrame())) {
				frameMatches.add(fi.getFrame());
				prototypes.add(fi);
			}
		}

		// Get frames that list this as an LU in the frame index
		LexicalUnit fnLU = s.getFNStyleLUUnsafe(
				headIdx, params.getTargetPruningData().getWordnetDict(), false);
		List<Frame> listedAsLUs = (fnLU == null)
				? Collections.<Frame>emptyList()
				: params.getTargetPruningData().getFramesFromLU(fnLU);
		for(Frame f : listedAsLUs) {
			if(uniqFrames.add(f)) {
				frameMatches.add(f);
				//prototypes.add(???);
			}
		}

		// Sometimes we'll have thing either mis-tagged or presented in a way
		// that is no in the lexical unit examples (e.g. "terrorist.a" is not
		// listed, while "terrorist.n" is). In this case, we want the option to
		// take all words, ignoring POS.
		if (params.useJustWordForPossibleFrames) {
			String w = s.getWord(headIdx);
			for (Frame f : params.getTargetPruningData().getLUFramesByWord(w)) {
				if (uniqFrames.add(f)) {
					frameMatches.add(f);
					//prototypes.add(???);
				}
			}
			// For a lot of NPs like "National Guard", the first word will get
			// tagged as NNP and framenet will list "national.a" as a LU.
			// We can re-cover if we ignore the POS tag, but we also need to
			// ensure that the case matches (which is typically lowercase for
			// adjectives).
			boolean alwaysLowercase = true;
			if (alwaysLowercase || headIdx == 0) {
				w = w.toLowerCase();
				for (Frame f : params.getTargetPruningData()
						.getLUFramesByWord(w)) {
					if (uniqFrames.add(f)) {
						frameMatches.add(f);
						//prototypes.add(???);
					}
				}
			}
		}

		// Infrequently, stemming messes up, "means" is listed for the Means
		// frame, but "mean" isn't
		for(Frame f : params.getTargetPruningData()
				.getFramesFromLU(s.getLU(headIdx))) {
			if(uniqFrames.add(f)) {
				frameMatches.add(f);
				//prototypes.add(???);
			}
		}

		// Sometimes the lemmatization/stemming is bad
		// e.g. "doses.NNS" => "dos.n"
		// try my own hair-brained plural-stripping
		if (params.useHackySingularSingularConversion &&
				s.getWord(headIdx).endsWith("es")) {
			String p = PosUtil.getPennToFrameNetTags().get(s.getPos(headIdx));
			if (p != null) {
				String w = s.getWord(headIdx);
				w = w.substring(0, w.length() - 1);
				LexicalUnit fnLUsing = new LexicalUnit(w, p);
				for(Frame f :
					params.getTargetPruningData().getFramesFromLU(fnLUsing)) {
					if(uniqFrames.add(f)) {
						frameMatches.add(f);
						//prototypes.add(???);
					}
				}
			}
		}

		// "thursday.n" is not an LU, but "Thursday.n" is
		if (params.lowercaseWordForPossibleFrames) {
			LexicalUnit fnLUlower = s.getFNStyleLUUnsafe(headIdx,
					params.getTargetPruningData().getWordnetDict(), true);
			if (fnLUlower != null) {
				for(Frame f :
					params.getTargetPruningData().getFramesFromLU(fnLUlower)) {
					if(uniqFrames.add(f)) {
						frameMatches.add(f);
						//prototypes.add(???);
					}
				}
			}
		}

		if(frameMatches.size() == 0)
			return null;

		return new FrameVars(headIdx, prototypes, frameMatches);
	}


	/**
	 * Takes a sentence, and optionally a FNTagging, and can make either
	 * {@link Decodable}<FNTagging>s (for prediction) or
	 * {@link LabeledFgExample}s for training.
	 * 
	 * @author travis
	 */
	public static class FrameIdDatum
			implements StageDatum<Sentence, FNTagging> {
		private final Sentence sentence;
		private final boolean hasGold;
		private final FNTagging gold;
		private final List<FrameVars> possibleFrames;
		private final FrameIdStage parent;

		public FrameIdDatum(Sentence s, FrameIdStage fid) {
			this.parent = fid;
			this.sentence = s;
			this.hasGold = false;
			this.gold = null;
			this.possibleFrames = new ArrayList<>();
			initHypotheses();
		}

		public FrameIdDatum(Sentence s, FrameIdStage fid, FNTagging gold) {
			assert gold != null;
			this.parent = fid;
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
				LOG.warn("skipping short sentence: " + sentence);
				return;
			}
			for(int i=0; i<n; i++) {
				FrameVars fv = makeFrameVar(sentence, i, parent.params);
				if(fv != null) possibleFrames.add(fv);
			}
		}

		private void setGold(FNTagging p) {
			if(p.getSentence() != sentence)
				throw new IllegalArgumentException();

			// Build an index from targetHeadIdx to FrameRoleVars
			Set<FrameVars> haventSet = new HashSet<FrameVars>();
			FrameVars[] byHead = new FrameVars[sentence.size()];
			for(FrameVars fHyp : this.possibleFrames) {
				assert byHead[fHyp.getTargetHeadIdx()] == null;
				byHead[fHyp.getTargetHeadIdx()] = fHyp;
				haventSet.add(fHyp);
			}

			// Match up each FI to a FIHypothesis by the head word in the target
			for(FrameInstance fi : p.getFrameInstances()) {
				Span target = fi.getTarget();
				int head = parent.getGlobalParams().headFinder.head(
						target, sentence);
				FrameVars fHyp = byHead[head];
				if(fHyp == null) continue;	// nothing you can do here
				if(fHyp.goldIsSet()) {
					System.err.println("WARNING: " + p.getId() +
							" has at least two FrameInstances with the same "
							+ "target head word, choosing the first one");
					continue;
				}
				fHyp.setGold(fi);
				boolean removed = haventSet.remove(fHyp);
				assert removed : "two FrameInstances with same head? "
						+ p.getSentence().getId();
			}

			// The remaining hypotheses must be null because they didn't
			// correspond to a FI in the parse
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

			// Add the gold labels
			for(FrameVars hyp : possibleFrames) {
				assert hyp.goldIsSet();
				hyp.register(fg, gold);
			}

			return new LabeledFgExample(fg, gold);
		}

		private FactorGraph getFactorGraph() {
			FactorGraph fg = new FactorGraph();

			// Create factors
			List<Factor> factors = new ArrayList<Factor>();
			ProjDepTreeFactor depTree = null;
			ConstituencyTreeFactor consTree = null;
			if (parent.getGlobalParams().useLatentConstituencies) {
				consTree = new ConstituencyTreeFactor(
						sentence.size(), VarType.LATENT);
				throw new RuntimeException(
						"FIXME: I don't know how this is supposed to work");
			}
			if (parent.getGlobalParams().useLatentDepenencies) {
				depTree = new ProjDepTreeFactor(
						sentence.size(), VarType.LATENT);
				DepParseFactorFactory depParseFactorTemplate =
						new DepParseFactorFactory(parent.getGlobalParams());
				factors.addAll(depParseFactorTemplate.initFactorsFor(
						sentence, Collections.emptyList(), depTree, consTree));
			}
			if (parent.getGlobalParams().useLatentConstituencies)
				throw new RuntimeException("implement me!");
			factors.addAll(parent.params.factorsTemplate.initFactorsFor(
					sentence, possibleFrames, depTree, consTree));

			// Add factors to the factor graph
			for(Factor f : factors)
				fg.addFactor(f);

			return fg;
		}

		@Override
		public FrameIdDecodable getDecodable() {
			FgInferencerFactory infFact = parent.infFactory();
			FactorGraph fg = this.getFactorGraph();
			return new FrameIdDecodable(
					sentence, possibleFrames, fg, infFact, parent);
		}

		@Override
		public FNTagging getGold() {
			assert hasGold();
			return gold;
		}
	}

	/**
	 * Stores beliefs about frameId variables (see {@link Decodable}) and
	 * implements the decoding step.
	 * 
	 * @author travis
	 */
	public static class FrameIdDecodable
			extends Decodable<FNTagging>
			implements Iterable<FrameVars> {

		private final FrameIdStage parent;
		private final Sentence sentence;
		private final List<FrameVars> possibleFrames;

		public FrameIdDecodable(Sentence sent, List<FrameVars> possibleFrames,
				FactorGraph fg, FgInferencerFactory infFact, FrameIdStage fid) {
			super(fg, infFact, fid);
			this.parent = fid;
			this.sentence = sent;
			this.possibleFrames = possibleFrames;
		}

		public Sentence getSentence() { return sentence; }

		@Override
		public Iterator<FrameVars> iterator() {
			return possibleFrames.iterator();
		}

		@Override
		public FNTagging decode() {
			FgInferencer hasMargins = this.getMargins();
			final boolean logDomain = this.getMargins().isLogDomain();
			List<FrameInstance> fis = new ArrayList<FrameInstance>();
			for(FrameVars fvars : possibleFrames) {
				final int T = fvars.numFrames();
				double[] beliefs = new double[T];
				for(int t=0; t<T; t++) {
					DenseFactor df =
							hasMargins.getMarginals(fvars.getVariable(t));
					// TODO Exactly1 factor removes the need for this
					if (logDomain) df.logNormalize();
					else df.normalize();
					beliefs[t] = df.getValue(BinaryVarUtil.boolToConfig(true));
				}
				parent.globalParams.normalize(beliefs);

				final int nullFrameIdx = fvars.getNullFrameIdx();
				int tHat = parent.params.decoder.decode(beliefs, nullFrameIdx);
				Frame fHat = fvars.getFrame(tHat);
				if(fHat != Frame.nullFrame)
					fis.add(FrameInstance.frameMention(fHat,
							Span.widthOne(fvars.getTargetHeadIdx()), sentence));
			}
			return new FNTagging(sentence, fis);
		}
	}
}
