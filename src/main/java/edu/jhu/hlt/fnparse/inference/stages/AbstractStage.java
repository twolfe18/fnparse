package edu.jhu.hlt.fnparse.inference.stages;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.inf.BeliefPropagation.BeliefPropagationPrm;
import edu.jhu.gm.inf.BeliefPropagation.BpScheduleType;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.FeatureCountFilter;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.util.Timer;

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
		return decode(this.setupInference(input));
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
		for(StageDatum<I, O> d : this.setupInference(unlabeledExamples).getStageData()) {
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
	

	
	//public void tuneRecallBias(List<Decodable<O>> examples, List<O> labels,
	public void tuneRecallBias(StageDatumExampleList<I, O> data,
			ApproxF1MbrDecoder decoder, EvalFunc obj, List<Double> biases) {

		// run inference and store the margins
		long t = System.currentTimeMillis();
		FgInferencerFactory infFact = this.infFactory();
		List<Decodable<O>> decodables = new ArrayList<>();
		List<O> labels = new ArrayList<>();
		for(StageDatum<I, O> x : data.getStageData()) {
			Decodable<O> d = x.getDecodable(infFact);
			d.force();
			decodables.add(d);
			assert x.hasGold();
			labels.add(x.getGold());
		}
		//for(Decodable<O> dec : examples) dec.force();
		long tInf = System.currentTimeMillis() - t;
		
		// decode many times and store performance
		t = System.currentTimeMillis();
		double originalBias = decoder.getRecallBias();
		double bestScore = Double.NEGATIVE_INFINITY;
		List<Double> scores = new ArrayList<Double>();
		for(double b : biases) {
			decoder.setRecallBias(b);
			List<O> predicted = new ArrayList<>();
			for(Decodable<O> m : decodables)
				predicted.add(m.decode());
			List<SentenceEval> instances = BasicEvaluation.zip(labels, predicted);
			double score = obj.evaluate(instances);
			System.out.printf("[tuneRecallBias %s] recallBias=%.2f %s=%.3f\n", globalParams.mode, b, obj.getName(), score);
			scores.add(score);
			if(score > bestScore) bestScore = score;
		}
		long tDec = System.currentTimeMillis() - t;

		List<Double> regrets = new ArrayList<Double>();
		for(double s : scores) regrets.add(100d * (bestScore - s));	// 100 percent instead of 1

		List<Double> weights = new ArrayList<Double>();
		for(double r : regrets) weights.add(Math.exp(-r * 2));

		double n = 0d, z = 0d;
		for(int i=0; i<biases.size(); i++) {
			double b = biases.get(i);
			double w = weights.get(i);
			n += w * b;
			z += w;
		}
		double bestBias = n / z;
		System.out.printf("[tuneRecalBias %s] took %.1f sec for inference and %.1f sec for decoding, done. recallBias %.2f => %.2f\n",
				globalParams.mode, tInf/1000d, tDec/1000d, originalBias, bestBias);
		decoder.setRecallBias(bestBias);
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
