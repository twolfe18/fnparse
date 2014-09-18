package edu.jhu.hlt.fnparse.inference.stages;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.inf.BeliefPropagation.BeliefPropagationPrm;
import edu.jhu.gm.inf.BeliefPropagation.BpScheduleType;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.gm.train.CrfTrainer.CrfTrainerPrm;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.FeatureCountFilter;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.hlt.optimize.AdaGrad;
import edu.jhu.hlt.optimize.AdaGrad.AdaGradPrm;
import edu.jhu.hlt.optimize.SGD;
import edu.jhu.hlt.optimize.SGD.SGDPrm;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.L2;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.util.Alphabet;

/**
 * Some helper code on top of Stage
 * 
 * @author travis
 *
 * @param <I> input to this stage
 * @param <O> output of this stage
 */
public abstract class AbstractStage<I, O extends FNTagging>
		implements Stage<I, O>, Serializable, HasFeatureAlphabet {
	private static final long serialVersionUID = 1L;

	protected final ParserParams globalParams;	// Not owned by this class
	protected FgModel weights;
	protected transient Logger log = Logger.getLogger(this.getClass());

	public AbstractStage(ParserParams params) {
		this.globalParams = params;
	}

	@Override
	public Alphabet<String> getFeatureAlphabet() {
		return globalParams.getFeatureAlphabet();
	}

	public String getName() {
		String[] ar = this.getClass().getName().split("\\.");
		return ar[ar.length-1];
	}

	@Override
	public FgModel getWeights() {
		if(weights == null) {
			throw new IllegalStateException(
					"you never initialized the weights");
		}
		return weights;
	}

	@Override
	public void setWeights(FgModel weights) {
		if (weights == null)
			throw new IllegalArgumentException();
		if (this.weights == null)
			this.weights = weights;
		else
			this.weights.setParams(weights.getParams());
	}

	@Override
	public boolean logDomain() {
		return globalParams.logDomain;
	}

	public FgInferencerFactory infFactory() {
		final BeliefPropagationPrm bpParams = new BeliefPropagationPrm();
		bpParams.normalizeMessages = false;
		bpParams.schedule = BpScheduleType.TREE_LIKE;
		bpParams.logDomain = globalParams.logDomain;
		bpParams.cacheFactorBeliefs = false;
		bpParams.maxIterations = 1;
		return new FgInferencerFactory() {
			@Override
			public boolean isLogDomain() { return bpParams.isLogDomain(); }
			@Override
			public FgInferencer getInferencer(FactorGraph fg) {
				return new BeliefPropagation(fg, bpParams);
			}
		};
	}

	/**
	 * A convenience method for calling decode on the input,
	 * which runs inference if it hasn't been run yet and then takes
	 * the output of that inference and decodes an answer.
	 */
	public List<O> decode(StageDatumExampleList<I, O> decodables) {
		List<O> decoded = new ArrayList<>();
		int n = decodables.size();
		for(int i=0; i<n; i++) {
			decoded.add(decodables
					.getStageDatum(i)
					.getDecodable()
					.decode());
		}
		return decoded;
	}

	public List<O> predict(List<I> input) {
		return decode(setupInference(input, null));
	}

	public void initWeights() {
		int numParams = getFeatureAlphabet().size();
		if(numParams == 0)
			throw new IllegalArgumentException("run AlphabetComputer first!");
		assert globalParams.verifyConsistency();
		weights = new FgModel(numParams);
	}

	/** initializes to a 0 mean Gaussian with diagnonal variance (provided) */
	public void randomlyInitWeights(final double variance, final Random r) {
		initWeights();
		weights.apply(new FnIntDoubleToDouble() {
			@Override
			public double call(int idx, double val) {
				return r.nextGaussian() * variance;
			}
		});
	}

	/** null means auto-select */
	public Double getLearningRate() {
		return null;
	}

	public Regularizer getRegularizer() {
		return new L2(1_000_000d);
	}

	public int getBatchSize() {
		return 4;
	}

	public int getNumTrainingPasses() {
		return 2;
	}

	@Override
	public void train(List<I> x, List<O> y) {
		train(x, y, getLearningRate(),
				getRegularizer(), getBatchSize(), getNumTrainingPasses());
	}

	/**
	 * @param x
	 * @param y
	 * @param learningRate if null pacaya will try to auto-select a learning rate
	 * @param regularizer
	 * @param batchSize
	 * @param passes is how many passes to make over the entire dataset
	 */
	public void train(List<I> x, List<O> y, Double learningRate,
			Regularizer regularizer, int batchSize, int passes) {
		assert globalParams.verifyConsistency();
		if (x.size() != y.size())
			throw new IllegalArgumentException("x.size=" + x.size() + ", y.size=" + y.size());
		Timer t = globalParams.getTimer(this.getName() + "-train");
		t.start();

		//initWeights();
		randomlyInitWeights(0.1d, new Random(9001));

		List<I> xTrain, xDev;
		List<O> yTrain, yDev;
		TuningData td = this.getTuningData();
		if(td == null) {
			xTrain = x;
			yTrain = y;
			xDev = Collections.emptyList();
			yDev = Collections.emptyList();
		} else {
			if (td.tuneOnTrainingData()) {
				xDev = xTrain = x;
				yDev = yTrain = y;
			} else {
				xTrain = new ArrayList<>();
				yTrain = new ArrayList<>();
				xDev = new ArrayList<>();
				yDev = new ArrayList<>();
				devTuneSplit(x, y, xTrain, yTrain, xDev, yDev,
						0.15d, 50, globalParams.rand);
			}
		}

		CrfTrainerPrm trainerParams = new CrfTrainerPrm();
		SGDPrm sgdParams = new SGDPrm();
		AdaGradPrm adagParams = new AdaGradPrm();
		if(learningRate == null) {
			sgdParams.autoSelectLr = true;
		} else {
			sgdParams.autoSelectLr = false;
			adagParams.eta = learningRate;
		}
		sgdParams.batchSize = batchSize;
		sgdParams.numPasses = passes;
		sgdParams.sched = new AdaGrad(adagParams);

		trainerParams.maximizer = null;
		trainerParams.batchMaximizer = new SGD(sgdParams);
		trainerParams.infFactory = infFactory();
		trainerParams.numThreads = globalParams.threads;
		trainerParams.regularizer = regularizer;

		Alphabet<String> alph = this.getFeatureAlphabet();
		log.info("Feature alphabet is frozen (size=" + alph.size() + "),"
				+ "going straight into training");
		alph.stopGrowth();

		// Get the data
		StageDatumExampleList<I, O> exs = this.setupInference(x, y);

		// Setup model and train
		CrfTrainer trainer = new CrfTrainer(trainerParams);
		try {
			weights = trainer.train(weights, exs);
		}
		catch(cc.mallet.optimize.OptimizationException oe) {
			oe.printStackTrace();
		}
		long timeTrain = t.stop();
		log.info(String.format(
				"Done training on %d examples for %.1f minutes, using %d features",
				exs.size(), timeTrain/(1000d*60d), alph.size()));

		// Tune
		if(td != null)
			tuneRecallBias(xDev, yDev, td);
	}

	/**
	 * forces the factor graphs to be created and the features to be computed,
	 * which has the side effect of populating the feature alphabet in params.
	 * @param labels may be null
	 */
	public void scanFeatures(
			List<? extends I> unlabeledExamples,
			List<? extends O> labels,
			double maxTimeInMinutes,
			int maxFeaturesAdded) {
		if (labels != null && unlabeledExamples.size() != labels.size())
			throw new IllegalArgumentException();
		if (!getFeatureAlphabet().isGrowing()) {
			throw new IllegalStateException("There is no reason to run this "
					+ "unless you've set the alphabet to be growing");
		}

		Timer t = globalParams.getTimer(this.getName() + "@scan-features");
		t.printIterval = 25;
		log.info("[scanFeatures] Counting the number of parameters needed over "
				+ unlabeledExamples.size() + " examples");

		// This stores counts in an array.
		// It gets the indices from the feature vectors, w/o knowing which
		// alphabet they came from.
		FeatureCountFilter fcount = new FeatureCountFilter();

		// Keep track of what parses we added so we can get a sense of our
		// frame/role coverage.
		List<FNTagging> seen = new ArrayList<>();

		final int alphSizeStart = getFeatureAlphabet().size();
		int examplesSeen = 0;
		int examplesWithNoFactorGraph = 0;
		StageDatumExampleList<I, O> data = this.setupInference(
				unlabeledExamples, null);
		int n = data.size();
		for(int i=0; i<n; i++) {
			t.start();
			StageDatum<I, O> d = data.getStageDatum(i);
			IDecodable<O> dec = d.getDecodable();
			if (dec instanceof Decodable)
				fcount.observe(((Decodable<O>) dec).getFactorGraph());
			else
				examplesWithNoFactorGraph++;
			examplesSeen++;
			t.stop();
	
			if(labels != null)
				seen.add(labels.get(i));

			if(t.totalTimeInSeconds() / 60d > maxTimeInMinutes) {
				log.info("[scanFeatures] Stopping because we used the max time "
						+ "(in minutes): " + maxTimeInMinutes);
				break;
			}
			int featuresAdded = getFeatureAlphabet().size() - alphSizeStart;
			if(featuresAdded > maxFeaturesAdded) {
				log.info("[scanFeatures] Stopping because we added the max "
						+ "allowed features: " + featuresAdded);
				break;
			}
		}

		if (examplesWithNoFactorGraph > 0) {
			log.warn("[scanFeatures] Some examples didn't have any FactorGraph "
					+ "associated with them: " + examplesWithNoFactorGraph);
		}

		if(seen.size() == 0) {
			log.info("[scanFeatures] Labels were provided, so we can't compute "
					+ "frame/role recall");
		} else {
			Set<Frame> fSeen = new HashSet<>();
			Set<String> rSeen = new HashSet<>();
			Set<String> frSeen = new HashSet<>();
			for(FNTagging tag : seen) {
				for(FrameInstance fi : tag.getFrameInstances()) {
					Frame f = fi.getFrame();
					fSeen.add(f);
					int K = f.numRoles();
					for(int k=0; k<K; k++) {
						Span a = fi.getArgument(k);
						if(a == Span.nullSpan) continue;
						String r = f.getRole(k);
						String fr = f.getName() + "." + r;
						rSeen.add(r);
						frSeen.add(fr);
					}
				}
			}
			log.info(String.format("[scanFeatures] Saw %d frames, "
					+ "%d frame-roles, and %d roles (ignoring frame)",
					fSeen.size(), frSeen.size(), rSeen.size()));
		}
		log.info(String.format("[scanFeatures] Done, scanned %d examples in "
				+ "%.1f minutes, alphabet size is %d, added %d",
				examplesSeen, t.totalTimeInSeconds() / 60d,
				getFeatureAlphabet().size(),
				getFeatureAlphabet().size()-alphSizeStart));
	}

	public static interface TuningData {

		public ApproxF1MbrDecoder getDecoder();

		/** Function to be maximized */
		public EvalFunc getObjective();

		public List<Double> getRecallBiasesToSweep();

		/** Return true if it is not necessary to split train and dev data */
		public boolean tuneOnTrainingData();
	}

	/**
	 * this is for specifying *how* to tune an {@link ApproxF1MbrDecoder}.
	 * if you don't have one to tune, then return null (the default implementation).
	 */
	public TuningData getTuningData() {
		return null;
	}

	public void tuneRecallBias(List<I> x, List<O> y, TuningData td) {
		if(x == null || y == null || x.size() != y.size())
			throw new IllegalArgumentException();
		if(td == null)
			throw new IllegalArgumentException();

		if(x.size() == 0) {
			log.warn("[tuneRecallBias] 0 examples were provided for tuning, skipping this");
			return;
		}

		log.info(String.format("[tuneRecallBias] Tuning to maximize %s on "
				+ "%d examples over biases in %s",
				td.getObjective().getName(), x.size(),
				td.getRecallBiasesToSweep()));

		// Run inference and store the margins
		long t = System.currentTimeMillis();

		List<Decodable<O>> decodables = new ArrayList<>();
		for(StageDatum<I, O> sd : this.setupInference(x, null).getStageData()) {
			Decodable<O> d = (Decodable<O>) sd.getDecodable();
			d.force();
			decodables.add(d);
		}
		long tInf = System.currentTimeMillis() - t;

		// Decode many times and store performance
		t = System.currentTimeMillis();
		double originalBias = td.getDecoder().getRecallBias();
		double bestScore = Double.NEGATIVE_INFINITY;
		List<Double> scores = new ArrayList<Double>();
		for(double b : td.getRecallBiasesToSweep()) {
			td.getDecoder().setRecallBias(b);
			List<O> predicted = new ArrayList<>();
			for(Decodable<O> m : decodables)
				predicted.add(m.decode());
			List<SentenceEval> instances = BasicEvaluation.zip(y, predicted);
			double score = td.getObjective().evaluate(instances);
			log.info(String.format("[tuneRecallBias] recallBias=%.2f %s=%.3f",
					b, td.getObjective().getName(), score));
			scores.add(score);
			if(score > bestScore) bestScore = score;
		}
		long tDec = System.currentTimeMillis() - t;

		List<Double> regrets = new ArrayList<Double>();
		for(double s : scores)
			regrets.add(100d * (bestScore - s));	// 100 percent instead of 1

		List<Double> weights = new ArrayList<Double>();
		for(double r : regrets) weights.add(Math.exp(-r * 2));

		double n = 0d, z = 0d;
		for(int i=0; i<td.getRecallBiasesToSweep().size(); i++) {
			double b = td.getRecallBiasesToSweep().get(i);
			double w = weights.get(i);
			n += w * b;
			z += w;
		}
		double bestBias = n / z;
		log.info(String.format("[tuneRecallBias] Took %.1f sec for inference and"
				+ " %.1f sec for decoding, done. recallBias %.2f => %.2f",
				tInf/1000d, tDec/1000d, originalBias, bestBias));
		td.getDecoder().setRecallBias(bestBias);
	}
	

	public static <A, B> void devTuneSplit(
			List<? extends A> x, List<? extends B> y,
			List<A> xTrain,      List<B> yTrain,
			List<A> xDev,        List<B> yDev,
			double propDev, int maxDev, Random r) {

		if (x.size() != y.size()) {
			throw new IllegalArgumentException(
					"x.size=" + x.size() + ", y.size=" + y.size());
		}
		if (xTrain.size() + yTrain.size() + xDev.size() + yDev.size() > 0)
			throw new IllegalArgumentException();
		if (x.size() == 0)
			return;

		final int n = x.size();
		for(int i=0; i<n; i++) {
			boolean train = r.nextDouble() > propDev;
			if(train) {
				xTrain.add(x.get(i));
				yTrain.add(y.get(i));
			}
			else {
				xDev.add(x.get(i));
				yDev.add(y.get(i));
			}
		}

		while(xDev.size() > maxDev) {
			xTrain.add(xDev.remove(xDev.size()-1));
			yTrain.add(yDev.remove(yDev.size()-1));
		}
	}
}
