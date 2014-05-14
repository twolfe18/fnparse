package edu.jhu.hlt.fnparse.inference.stages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.inf.BeliefPropagation.BeliefPropagationPrm;
import edu.jhu.gm.inf.BeliefPropagation.BpScheduleType;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.gm.train.CrfTrainer.CrfTrainerPrm;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.FeatureCountFilter;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.hlt.optimize.AdaGrad;
import edu.jhu.hlt.optimize.AdaGrad.AdaGradPrm;
import edu.jhu.hlt.optimize.SGD;
import edu.jhu.hlt.optimize.SGD.SGDPrm;
import edu.jhu.util.Alphabet;

/**
 * Some helper code on top of Stage
 * 
 * @author travis
 *
 * @param <I>
 * @param <O>
 */
public abstract class AbstractStage<I, O extends FNTagging> implements Stage<I, O> {
	
	protected ParserParams globalParams;
	
	public AbstractStage(ParserParams params) {
		this.globalParams = params;
	}
	
	public String getName() {
		String[] ar = this.getClass().getName().split("\\.");
		return ar[ar.length-1];
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
		FgInferencerFactory infFact = this.infFactory();
		List<O> decoded = new ArrayList<>();
		int n = decodables.size();
		for(int i=0; i<n; i++)
			decoded.add(decodables.getStageDatum(i).getDecodable(infFact).decode());
		return decoded;
	}

	
	public List<O> predict(List<I> input) {
		return decode(this.setupInference(input, null));
	}


	@Override
	public void train(List<I> x, List<O> y) {
		int batchSize = 4;
		int passes = 2;
		Double learningRate = null;	// null means auto select
		train(x, y, learningRate, batchSize, passes);
	}

	public void train(List<I> x, List<O> y, Double learningRate, int batchSize, int passes) {
		
		if(globalParams.featIdx.size() == 0)
			throw new IllegalArgumentException("run AlphabetComputer first!");
		assert globalParams.verifyConsistency();
		Timer t = globalParams.timer.get(this.getName() + "-train", true);
		t.start();
		
		List<I> xTrain, xDev;
		List<O> yTrain, yDev;
		TuningData td = this.getTuningData();
		if(td == null) {
			xTrain = x;
			yTrain = y;
			xDev = Collections.emptyList();
			yDev = Collections.emptyList();
		}
		else {
			xTrain = new ArrayList<>();
			yTrain = new ArrayList<>();
			xDev = new ArrayList<>();
			yDev = new ArrayList<>();
			devTuneSplit(x, y, xTrain, yTrain, xDev, yDev, 0.15d, 50, globalParams.rand);
		}
		
		CrfTrainerPrm trainerParams = new CrfTrainerPrm();
		SGDPrm sgdParams = new SGDPrm();
		AdaGradPrm adagParams = new AdaGradPrm();
		if(learningRate == null)
			sgdParams.autoSelectLr = true;
		else {
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

		Alphabet<String> alph = globalParams.featIdx;
		System.out.printf("[%s train] alphabet is frozen (size=%d), going straight into training\n", this.getName(), alph.size());
		alph.stopGrowth();
		
		// get the data
		StageDatumExampleList<I, O> exs = this.setupInference(x, y);
		
		// setup model and train
		CrfTrainer trainer = new CrfTrainer(trainerParams);
		try {
			globalParams.weights = trainer.train(globalParams.weights, exs);
		}
		catch(cc.mallet.optimize.OptimizationException oe) {
			oe.printStackTrace();
		}
		long timeTrain = t.stop();
		System.out.printf("[%s train] done training on %d examples for %.1f minutes\n", this.getName(), exs.size(), timeTrain/(1000d*60d));
		System.out.printf("[%s train] params.featIdx.size=%d\n", this.getName(), alph.size());

		// tune
		if(td != null)
			tuneRecallBias(xDev, yDev, td);
	}

	
	/**
	 * forces the factor graphs to be created and the features to be computed,
	 * which has the side effect of populating the feature alphabet in this.params.
	 */
	public void scanFeatures(List<? extends I> unlabeledExamples, double maxTimeInMinutes) {

		Timer t = globalParams.timer.get("scan-features", true);
		t.start();
		System.out.println("[scanFeatures] counting the number of parameters needed over " +
				unlabeledExamples.size() + " examples");

		// this stores counts in an array
		// it gets the indices from the feature vectors, w/o knowing which alphabet they came from
		FeatureCountFilter fcount = new FeatureCountFilter();

		int examplesSeen = 0;
		FgInferencerFactory infFact = this.infFactory();
		for(StageDatum<I, O> d : this.setupInference(unlabeledExamples, null).getStageData()) {
			fcount.observe(d.getDecodable(infFact).getFactorGraph());
			examplesSeen++;

			if(t.totalTimeInSeconds() / 60d > maxTimeInMinutes) {
				System.out.println("[scanFeatures] stopping because we used the max time (in minutes): " + maxTimeInMinutes);
				break;
			}
		}

		t.stop();
		System.out.printf("[scanFeatures] done, scanned %d examples in %.1f minutes, alphabet size is %d\n",
			examplesSeen, t.totalTimeInSeconds() / 60d, globalParams.featIdx.size());
	}
	
	
	public static interface TuningData {

		public ApproxF1MbrDecoder getDecoder();

		/** this is maximized */
		public EvalFunc getObjective();

		public List<Double> getRecallBiasesToSweep();
	}

	
	/**
	 * this is for specifying *how* to tune an {@link ApproxF1MbrDecoder}.
	 * if you don't have one to tune, then return null (the default implementation).
	 */
	public TuningData getTuningData() {
		return null;
	}

	
	public void tuneRecallBias(List<I> x, List<O> y, TuningData td) {
		
		if(x == null || y == null || x.size() != y.size() || x.size() == 0)
			throw new IllegalArgumentException();
		if(td == null)
			throw new IllegalArgumentException();

		System.out.printf("[%s tuneRecallBias] tuning to maximize %s on %d examples over biases in %s\n",
				this.getName(), td.getObjective().getName(), x.size(), td.getRecallBiasesToSweep());

		// run inference and store the margins
		long t = System.currentTimeMillis();
		FgInferencerFactory infFact = this.infFactory();
		
		List<Decodable<O>> decodables = new ArrayList<>();
		List<O> labels = new ArrayList<>();
		for(StageDatum<I, O> sd : this.setupInference(x, null).getStageData()) {
			Decodable<O> d = sd.getDecodable(infFact);
			d.force();
			decodables.add(d);
		}
		long tInf = System.currentTimeMillis() - t;
		
		// decode many times and store performance
		t = System.currentTimeMillis();
		double originalBias = td.getDecoder().getRecallBias();
		double bestScore = Double.NEGATIVE_INFINITY;
		List<Double> scores = new ArrayList<Double>();
		for(double b : td.getRecallBiasesToSweep()) {
			td.getDecoder().setRecallBias(b);
			List<O> predicted = new ArrayList<>();
			for(Decodable<O> m : decodables)
				predicted.add(m.decode());
			List<SentenceEval> instances = BasicEvaluation.zip(labels, predicted);
			double score = td.getObjective().evaluate(instances);
			System.out.printf("[%s tuneRecallBias] recallBias=%.2f %s=%.3f\n", this.getName(), b, td.getObjective().getName(), score);
			scores.add(score);
			if(score > bestScore) bestScore = score;
		}
		long tDec = System.currentTimeMillis() - t;

		List<Double> regrets = new ArrayList<Double>();
		for(double s : scores) regrets.add(100d * (bestScore - s));	// 100 percent instead of 1

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
		System.out.printf("[%s tuneRecalBias] took %.1f sec for inference and %.1f sec for decoding, done. recallBias %.2f => %.2f\n",
				this.getName(), tInf/1000d, tDec/1000d, originalBias, bestBias);
		td.getDecoder().setRecallBias(bestBias);
	}
	

	public static <A, B> void devTuneSplit(
			List<? extends A> x, List<? extends B> y,
			List<A> xTrain,      List<B> yTrain,
			List<A> xDev,        List<B> yDev,
			double propDev, int maxDev, Random r) {
		
		if(x.size() != y.size() || x.size() == 0)
			throw new IllegalArgumentException();
		if(xTrain.size() + yTrain.size() + xDev.size() + yDev.size() > 0)
			throw new IllegalArgumentException();
		
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
	
	
	public static <T extends FNTagging> List<T> filterBySentenceLength(List<T> all, int maxLength) {
		List<T> list = new ArrayList<>();
		for(T t : all)
			if(t.getSentence().size() <= maxLength)
				list.add(t);
		return list;
	}
}
