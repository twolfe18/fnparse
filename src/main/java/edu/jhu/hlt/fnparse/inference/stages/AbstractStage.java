package edu.jhu.hlt.fnparse.inference.stages;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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
import edu.jhu.hlt.fnparse.util.ModelIO;
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
		implements Stage<I, O>, Serializable {
	private static final long serialVersionUID = 1L;

	public static boolean DEBUG_SER = false;

	protected final ParserParams globalParams; // Not owned by this class
	protected FgModel weights;
	protected boolean scanFeaturesHasBeenRun = false;
	protected transient HasFeatureAlphabet featureNames;
	protected transient Logger log = Logger.getLogger(this.getClass());

	public AbstractStage(ParserParams params, HasFeatureAlphabet featureNames) {
		this.globalParams = params;
		this.featureNames = featureNames;
	}

	public ParserParams getGlobalParams() {
		return globalParams;
	}

	public String getName() {
		String[] ar = this.getClass().getName().split("\\.");
		return ar[ar.length-1];
	}

	/**
	 * Return your parameters other than the weights
	 * (this is used to implement saveModel)
	 */
	public abstract Serializable getParamters();

	/**
	 * Set the parameters that were returned by getParamters
	 * (this is used to implement loadModel)
	 */
	public abstract void setPameters(Serializable params);

	@Override
	public void saveModel(File file) {
		log.info("[saveModel] writing to " + file.getPath());
		try {
			DataOutputStream dos = new DataOutputStream(
					new GZIPOutputStream(new FileOutputStream(file)));
			ObjectOutputStream oos = new ObjectOutputStream(dos);
			oos.writeObject(getParamters());
			double[] ps = new double[weights.getNumParams()];
			weights.updateDoublesFromModel(ps);
			ModelIO.writeFeatureNameWeightsBinary(
					ps, featureNames.getAlphabet(), dos);
			oos.close();  // closes dos too

			if (DEBUG_SER) {
				for (int i = 0; i < ps.length; i++) {
					String fn = featureNames.getAlphabet().lookupObject(i);
					log.debug("[saveModel] " + fn + "\t" + ps[i]);
				}
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void loadModel(File file) {
		log.info("[loadModel] reading from " + file.getPath());
		try {
			DataInputStream dis = new DataInputStream(
					new GZIPInputStream(new FileInputStream(file)));
			ObjectInputStream ois = new ObjectInputStream(dis);
			setPameters((Serializable) ois.readObject());
			double[] ps = ModelIO.readFeatureNameWeightsBinary(
					dis, featureNames.getAlphabet());
			weights = new FgModel(ps.length);
			weights.updateModelFromDoubles(ps);
			ois.close();

			if (DEBUG_SER) {
				for (int i = 0; i < ps.length; i++) {
					String fn = featureNames.getAlphabet().lookupObject(i);
					log.debug("[loadModel] " + fn + "\t" + ps[i]);
				}
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public FgModel getWeights() {
		if (weights == null) {
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
	 * A convenience method for calling decode on the input, which runs inference
	 * if it hasn't been run yet and then takes the output of that inference and
	 * decodes an answer.
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
		int numParams = featureNames.getAlphabet().size();
		if(numParams == 0) {
		  log.warn("[initWeights] no parameters!");
		  assert scanFeaturesHasBeenRun;
		}
		assert globalParams.verifyConsistency();
		if (weights != null && weights.getNumParams() > 0)
			log.warn("re-initializing paramters!");
		weights = new FgModel(numParams);
	}

	/** initializes to a 0 mean Gaussian with diagnonal variance (provided) */
	public void randomlyInitWeights(final double variance, final Random r) {
		log.info("randomly initializing weights with a variance of " + variance);
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
		log.info("starting training");
		Timer t = globalParams.getTimer(this.getName() + "-train");
		t.start();

		//initWeights();
		randomlyInitWeights(0.1d, new Random(9001));

		List<I> xTrain, xDev;
		List<O> yTrain, yDev;
		TuningData td = this.getTuningData();
		if (td == null) {
		  log.info("performing no dev tuning");
			xTrain = x;
			yTrain = y;
			xDev = Collections.emptyList();
			yDev = Collections.emptyList();
		} else {
			if (td.tuneOnTrainingData()) {
			  log.info("tuning on train data");
				xDev = xTrain = x;
				yDev = yTrain = y;
			} else {
			  log.info("tuning on held-out data");
				xTrain = new ArrayList<>();
				yTrain = new ArrayList<>();
				xDev = new ArrayList<>();
				yDev = new ArrayList<>();
				devTuneSplit(x, y, xTrain, yTrain, xDev, yDev,
						0.15d, 50, globalParams.rand);
			}
		}
		log.info("[train] #train=" + xTrain.size() + " #tune=" + xDev.size());

		CrfTrainerPrm trainerParams = new CrfTrainerPrm();
		SGDPrm sgdParams = new SGDPrm();
		AdaGradPrm adagParams = new AdaGradPrm();
		if (learningRate == null) {
		  log.info("[train] automatically selecting learning rate");
			sgdParams.autoSelectLr = true;
		} else {
		  log.info("[train] learningRate=" + learningRate);
			sgdParams.autoSelectLr = false;
			adagParams.eta = learningRate;
		}
		sgdParams.batchSize = batchSize;
		sgdParams.numPasses = passes;
		sgdParams.sched = new AdaGrad(adagParams);
		log.info("[train] passes=" + passes + " batchSize=" + batchSize);
		log.info("[train] regularizer=" + regularizer);

		trainerParams.maximizer = null;
		trainerParams.batchMaximizer = new SGD(sgdParams);
		trainerParams.infFactory = infFactory();
		trainerParams.numThreads = 1; //globalParams.threads;
		trainerParams.regularizer = regularizer;
		log.info("[train] numThreads=" + trainerParams.numThreads);

		Alphabet<String> alph = featureNames.getAlphabet();
		log.info("[train] Feature alphabet is frozen (size=" + alph.size() + "), "
				+ "going straight into training");
		alph.stopGrowth();

		// Get the data
		StageDatumExampleList<I, O> exs = this.setupInference(x, y);

		// Setup model and train
		CrfTrainer trainer = new CrfTrainer(trainerParams);
		try {
			weights = trainer.train(weights, exs);
		} catch(cc.mallet.optimize.OptimizationException oe) {
			oe.printStackTrace();
		}
		long timeTrain = t.stop();
		log.info(String.format(
				"Done training on %d examples for %.1f minutes, using %d features",
				exs.size(), timeTrain/(1000d*60d), alph.size()));

		// Tune
		if(td != null)
			tuneRecallBias(xDev, yDev, td);

		log.info("done training");
	}

	/**
	 * forces the factor graphs to be created and the features to be computed,
	 * which has the side effect of populating the feature alphabet in params.
	 * @param labels may be null
	 */
	@Override
	public void scanFeatures(
			List<? extends I> unlabeledExamples,
			List<? extends O> labels,
			double maxTimeInMinutes,
			int maxFeaturesAdded) {
		if (labels != null && unlabeledExamples.size() != labels.size())
			throw new IllegalArgumentException();
		if (!featureNames.getAlphabet().isGrowing()) {
			throw new IllegalStateException("There is no reason to run this "
					+ "unless you've set the alphabet to be growing");
		}

		Timer t = globalParams.getTimer(this.getName() + "@scan-features");
		t.printIterval = 500;
		log.info("[scanFeatures] Counting the number of parameters needed over "
				+ unlabeledExamples.size() + " examples");

		// This stores counts in an array.
		// It gets the indices from the feature vectors, w/o knowing which
		// alphabet they came from.
		FeatureCountFilter fcount = new FeatureCountFilter();

		// Keep track of what parses we added so we can get a sense of our
		// frame/role coverage.
		List<FNTagging> seen = new ArrayList<>();

		final int alphSizeStart = featureNames.getAlphabet().size();
		int examplesSeen = 0;
		int examplesWithNoFactorGraph = 0;
		StageDatumExampleList<I, O> data = this.setupInference(
				unlabeledExamples, null);
		int n = data.size();
		for (int i = 0; i < n; i++) {
			t.start();
			StageDatum<I, O> d = data.getStageDatum(i);
			IDecodable<O> dec = d.getDecodable();
			if (dec instanceof Decodable)
				fcount.observe(((Decodable<O>) dec).getFactorGraph());
			else
				examplesWithNoFactorGraph++;
			examplesSeen++;
			t.stop();

			if (labels != null)
				seen.add(labels.get(i));

			if (t.totalTimeInSeconds() / 60d > maxTimeInMinutes) {
				log.info("[scanFeatures] Stopping because we used the max time "
						+ "(in minutes): " + maxTimeInMinutes);
				break;
			}
			int featuresAdded = featureNames.getAlphabet().size()
					- alphSizeStart;
			if (featuresAdded > maxFeaturesAdded) {
				log.info("[scanFeatures] Stopping because we added the max "
						+ "allowed features: " + featuresAdded);
				break;
			}
		}

		if (examplesWithNoFactorGraph > 0) {
			log.warn("[scanFeatures] Some examples didn't have any FactorGraph "
					+ "associated with them: " + examplesWithNoFactorGraph);
		}

		if (seen.size() == 0) {
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
				featureNames.getAlphabet().size(),
				featureNames.getAlphabet().size() - alphSizeStart));
		scanFeaturesHasBeenRun = true;
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
		if (x == null || y == null || x.size() != y.size())
			throw new IllegalArgumentException();
		if (td == null)
			throw new IllegalArgumentException();

		if (x.size() == 0) {
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
		for (StageDatum<I, O> sd : this.setupInference(x, null).getStageData()) {
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
		for (double b : td.getRecallBiasesToSweep()) {
			td.getDecoder().setRecallBias(b);
			List<O> predicted = new ArrayList<>();
			for (Decodable<O> m : decodables)
				predicted.add(m.decode());
			List<SentenceEval> instances = BasicEvaluation.zip(y, predicted);
			double score = td.getObjective().evaluate(instances);
			log.info(String.format("[tuneRecallBias] recallBias=%.2f %s=%.3f",
					b, td.getObjective().getName(), score));
			scores.add(score);
			if (score > bestScore) bestScore = score;
		}
		long tDec = System.currentTimeMillis() - t;

		List<Double> regrets = new ArrayList<Double>();
		for(double s : scores)
			regrets.add(bestScore - s);

		List<Double> weights = new ArrayList<Double>();
		for(double r : regrets) weights.add(Math.exp(-r * 200d));

		double n = 0d, z = 0d;
		for (int i=0; i<td.getRecallBiasesToSweep().size(); i++) {
			double b = td.getRecallBiasesToSweep().get(i);
			double w = weights.get(i);
			n += w * b;
			z += w;
		}
		double bestBias = n / z;
		log.info(String.format("[tuneRecallBias] Took %.1f sec for inference and"
				+ " %.1f sec for decoding, done. recallBias %.2f => %.2f @ %.3f",
				tInf/1000d, tDec/1000d, originalBias, bestBias, bestScore));
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
		for (int i=0; i<n; i++) {
			boolean train = r.nextDouble() > propDev;
			if (train) {
				xTrain.add(x.get(i));
				yTrain.add(y.get(i));
			} else {
				xDev.add(x.get(i));
				yDev.add(y.get(i));
			}
		}

		while (xDev.size() > maxDev) {
			xTrain.add(xDev.remove(xDev.size()-1));
			yTrain.add(yDev.remove(yDev.size()-1));
		}
	}
}
