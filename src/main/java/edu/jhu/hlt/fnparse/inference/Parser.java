package edu.jhu.hlt.fnparse.inference;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleCache;
import edu.jhu.gm.data.FgExampleList;
import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.inf.BeliefPropagation.BeliefPropagationPrm;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.BasicRoleDepFeatures;
import edu.jhu.hlt.fnparse.features.BasicRoleFeatures;
import edu.jhu.hlt.fnparse.features.BasicRoleSpanFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingFrameDepFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingFrameFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingRoleFeatures;
import edu.jhu.hlt.fnparse.features.FeatureCountFilter;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.features.caching.RawExampleFactory;
import edu.jhu.hlt.fnparse.inference.frameid.FrameFactorFactory;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdSentence;
import edu.jhu.hlt.fnparse.inference.frameid.FrameVars;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.inference.jointid.FrameInstanceHypothesis;
import edu.jhu.hlt.fnparse.inference.jointid.JointFactorFactory;
import edu.jhu.hlt.fnparse.inference.jointid.JointFrameRoleIdSentence;
import edu.jhu.hlt.fnparse.inference.pruning.ArgPruner;
import edu.jhu.hlt.fnparse.inference.pruning.IArgPruner;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.inference.roleid.RoleFactorFactory;
import edu.jhu.hlt.fnparse.inference.roleid.RoleIdSentence;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.hlt.optimize.AdaGrad;
import edu.jhu.hlt.optimize.SGD;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.HeterogeneousL2;
import edu.jhu.hlt.util.stats.Multinomials;
import edu.jhu.util.Alphabet;


public class Parser {
	
	public static enum Mode {
		FRAME_ID,
		PIPELINE_FRAME_ARG,
		JOINT_FRAME_ARG
	}
	
	public static class ParserParams implements Serializable {
		
		private static final long serialVersionUID = 1L;
		
		public boolean debug;
		public boolean logDomain;
		public boolean useLatentDepenencies;
		public boolean usePrototypes;
		public boolean useSyntaxFeatures;
		public boolean fastFeatNames;	// if false, use frame and role names instead of their indices
		public boolean usePredictedFramesToTrainRoleId;	// otherwise use gold frames
		
		public Mode mode;
		public Alphabet<String> featIdx;
		public FgModel model;
		public HeadFinder headFinder;
		public ApproxF1MbrDecoder frameDecoder;
		public ApproxF1MbrDecoder argDecoder;
		public TargetPruningData targetPruningData;
		public IArgPruner argPruner;
		public int maxTrainSentenceLength;
		
		public Features.F  fFeatures;
		public Features.FD fdFeatures;
		public Features.R  rFeatures;
		public Features.RD rdFeatures;
		public Features.RE reFeatures;

		// these are additive (as in when doing jointId, you include factors from factorsForFrameId)
		public FactorFactory<FrameVars> factorsForFrameId;
		public FactorFactory<RoleVars> factorsForRoleId;
		public FactorFactory<FrameInstanceHypothesis> factorsForJointId;
		
		/** checks if they're log proportions from this.logDomain */
		public void normalize(double[] proportions) {
			if(this.logDomain)
				Multinomials.normalizeLogProps(proportions);
			else
				Multinomials.normalizeProps(proportions);
		}
	}
	
	
	public ParserParams params;
	public final boolean readIn;
	public final boolean benchmarkBP = false;
	
	public Parser() {
		this(Mode.PIPELINE_FRAME_ARG, false, false);
	}
	
	public Parser(File f) {
		System.out.println("[Parser] reading parser from " + f.getPath());
		long start = System.currentTimeMillis();
		try {
			InputStream is = new FileInputStream(f);
			if(f.getName().toLowerCase().endsWith(".gz"))
				is = new GZIPInputStream(is);
			ObjectInputStream ois = new ObjectInputStream(is);
			params = (ParserParams) ois.readObject();
			ois.close();
			readIn = true;
			System.out.printf("[Parser] done reading model in %.1f seconds (%d known features, weights.l2=%.2f)\n",
					(System.currentTimeMillis() - start)/1000d, params.featIdx.size(), params.model.l2Norm());
		}
		catch(Exception e) { throw new RuntimeException(e); }
	}
	
	public Parser(Mode mode, boolean latentDeps, boolean debug) {

		readIn = false;
		params = new ParserParams();
		params.debug = debug;
		params.featIdx = new Alphabet<String>();
		params.logDomain = false;
		params.useLatentDepenencies = latentDeps;
		params.mode = mode;
		params.usePrototypes = false;
		params.useSyntaxFeatures = true;
		params.usePredictedFramesToTrainRoleId = true;
		params.fastFeatNames = debug;
		params.targetPruningData = TargetPruningData.getInstance();

		params.headFinder = new SemaforicHeadFinder();
		params.frameDecoder = new ApproxF1MbrDecoder(params.logDomain, 1.5d);
		params.argDecoder = new ApproxF1MbrDecoder(params.logDomain, 1.5d);
		params.argPruner = new ArgPruner(params);
		params.maxTrainSentenceLength = 50;	// <= 0 for no pruning
		
		params.factorsForFrameId = new FrameFactorFactory(params, latentDeps, latentDeps);
		params.factorsForRoleId = new RoleFactorFactory(params, latentDeps, latentDeps, false);
		params.factorsForJointId = new JointFactorFactory(params);
		
		if(params.debug) {
			params.fFeatures = new DebuggingFrameFeatures(params);
			params.fdFeatures = new DebuggingFrameDepFeatures(params);
			params.rFeatures = new DebuggingRoleFeatures(params);
			params.rdFeatures = new BasicRoleDepFeatures(params);
			//params.reFeatures = new DebuggingRoleSpanFeatures(params);	// make sure these are not used on real data -- they will overfit
			params.reFeatures = new BasicRoleSpanFeatures(params);
		}
		else {
			params.fFeatures = new BasicFrameFeatures(params);
			params.fdFeatures = new DebuggingFrameDepFeatures(params);
			params.rFeatures = new BasicRoleFeatures(params);
			params.rdFeatures = new BasicRoleDepFeatures(params);
			params.reFeatures = new BasicRoleSpanFeatures(params);
		}
	}
	
	public void setMode(Mode m, boolean useLatentDeps) {
		if(params.useLatentDepenencies != useLatentDeps && params.mode != null && params.model.l2Norm() > 1e-4)
			throw new RuntimeException("changing this on a trained model will break things");
		params.mode = m;
		params.useLatentDepenencies = useLatentDeps;
	}
	
	public ParserParams getParams() { return params; }
	
	public FgInferencerFactory infFactory() {
		final BeliefPropagationPrm bpParams = new BeliefPropagationPrm();
		bpParams.normalizeMessages = true;
		bpParams.logDomain = params.logDomain;
		bpParams.cacheFactorBeliefs = false;
		bpParams.maxIterations = params.useLatentDepenencies ? 10 : 2;
		return new FgInferencerFactory() {
			@Override
			public boolean isLogDomain() { return bpParams.isLogDomain(); }
			@Override
			public FgInferencer getInferencer(FactorGraph fg) {
				if(benchmarkBP)
					return new BenchmarkingBP(fg, bpParams);
				else
					return new BeliefPropagation(fg, bpParams);
			}
		};
	}
	
	public Regularizer getRegularizer(int numParams, double regularizerMult) {
		
		List<Integer> dontRegularize = new ArrayList<Integer>();
		for(FactorFactory<?> ff : Arrays.asList(params.factorsForFrameId, params.factorsForRoleId, params.factorsForJointId)) {
			if(ff == null) continue;
			for(Features f : ff.getFeatures()) {
				if(f == null)
					throw new RuntimeException("dont return null features: " + ff);
				dontRegularize.addAll(f.dontRegularize());
			}
		}
		System.out.printf("[getRegularizer] not regularizing %d parameters\n", dontRegularize.size());

		// L2's parameter is variance => bigger means less regularization
		// L1's parameter is multiplier => bigger means more regularization
		//return new L2(10d);
		return HeterogeneousL2.zeroMeanIgnoringIndices(dontRegularize, regularizerMult, numParams);
	}
	
	public List<LabeledFgExample> getExampleForTraining(FNParse p) {
		
		if(params.mode == Mode.FRAME_ID) {
			FrameIdSentence s = new FrameIdSentence(p.getSentence(), params, p);
			return Arrays.asList(s.getTrainingExample());
		}
		else if(params.mode == Mode.JOINT_FRAME_ARG) {
			JointFrameRoleIdSentence s = new JointFrameRoleIdSentence(p.getSentence(), params, p);
			return Arrays.asList(s.getTrainingExample());
		}
		else if(params.mode == Mode.PIPELINE_FRAME_ARG) {
			FNTagging predictedFrames = p;
			if(params.usePredictedFramesToTrainRoleId) {
				FrameIdSentence fid = new FrameIdSentence(p.getSentence(), params);
				predictedFrames = fid.decode(params.model, infFactory());
			}
			RoleIdSentence argId = new RoleIdSentence(p.getSentence(), predictedFrames, params, p);
			LabeledFgExample e = argId.getTrainingExample();
			return Arrays.asList(e);
		}
		else throw new RuntimeException();
	}


	/** returns a modified alphabet */
	private Alphabet<String> scanFeatures(FgExampleList exs) {

		long start = System.currentTimeMillis();
		System.out.println("[scanFeatures] counting the number of parameters needed");
		
		// keep the set of features that are already in the model.
		// e.g. you trained a frameId model, read it in, and now you want to train roleId.
		//      you want to keep all of the frameId features and only filter the roleId features.
		Alphabet<String> preExisting = new Alphabet<>(params.featIdx);
		
		FeatureCountFilter fcount = new FeatureCountFilter();

		int maxIncrease = 0;
		int maxExamplesInARow = 10;

		int minExamples = 100;
		int maxExamples = 5000;

		int prevSize = params.featIdx.size();
		int examplesSeen = 0;
		int sameInARow = 0;
		for(FgExample fge : exs) {
			examplesSeen++;
			fcount.observe(fge);
			if(examplesSeen >= maxExamples) {
				System.out.println("[scanFeatures] stopping because we saw " + maxExamples + " graphs");
				break;
			}
			int size = params.featIdx.size();
			if(size - prevSize <= maxIncrease)
				sameInARow++;
			else sameInARow = 0;
			if(sameInARow >= maxExamplesInARow && examplesSeen >= minExamples) {
				System.out.printf("[scanFeatures] stopping because we saw %d examples in a row where the alphabet grew by no more than %d\n",
					sameInARow, maxIncrease);
				break;
			}
			prevSize = size;
		}
		System.out.printf("[scanFeatures] done, scanned %d examples in %.1f minutes, alphabet size is %d\n",
			examplesSeen, (System.currentTimeMillis() - start) / (1000d * 60d), params.featIdx.size());
		
		if(params.debug) {
			System.out.println("[scanFeatures] not filtering features because we're in debug mode");
			return params.featIdx;
		}
		else {
			int minFeatureOccurrences = 3;
			Alphabet<String> newFeatIdx = fcount.filterByCount(params.featIdx, minFeatureOccurrences, preExisting);
			for(FgExample fge : exs)
				fcount.prune(fge);
			return newFeatIdx;
		}
	}


	public void train(List<FNParse> examples) { train(examples, 10, 5, 1d, 1d); }
	public void train(List<FNParse> examples, int passes, int batchSize, double learningRateMultiplier, double regularizerMult) {
		
		System.out.println("[Parser train] starting training in " + params.mode + " mode...");
		Logger.getLogger(CrfTrainer.class).setLevel(Level.ALL);
		long start = System.currentTimeMillis();
		
		if(params.mode != Mode.FRAME_ID && params.maxTrainSentenceLength > 0) {
			List<FNParse> notHuge = new ArrayList<FNParse>();
			for(FNParse p : examples)
				if(p.getSentence().size() <= params.maxTrainSentenceLength)
					notHuge.add(p);
			System.out.printf("[Parser train] filtering out sentences longer than %d words, kept %d of %d examples\n",
					params.maxTrainSentenceLength, notHuge.size(), examples.size());
			examples = notHuge;
		}
		
		CrfTrainer.CrfTrainerPrm trainerParams = new CrfTrainer.CrfTrainerPrm();

		AdaGrad.AdaGradPrm adagParams = new AdaGrad.AdaGradPrm();
		adagParams.eta = learningRateMultiplier;
		
		SGD.SGDPrm sgdParams = new SGD.SGDPrm();
		sgdParams.batchSize = batchSize;
		sgdParams.numPasses = passes;
		sgdParams.sched = new AdaGrad(adagParams);

		trainerParams.maximizer = null;
		trainerParams.batchMaximizer = new SGD(sgdParams);
		trainerParams.infFactory = infFactory();
		trainerParams.numThreads = 1;	// can't do multithreaded until i make sure my feature extraction is serial (alphabet updates need locking)
		
		// setup the feature extraction
		int keepInMemory = params.mode == Mode.FRAME_ID ? 15000 : 10;
		RawExampleFactory rexs = new RawExampleFactory(examples, this);
		FgExampleCache exs = new FgExampleCache(rexs, keepInMemory, false);
		if(params.debug || true) {
			int lim = params.mode == Mode.FRAME_ID
					? 150
					: (params.mode == Mode.PIPELINE_FRAME_ARG ? 30 : 1);
			rexs.setTimerPrintInterval(lim);
		}

		// compute how many features we need
		params.featIdx.startGrowth();
		params.featIdx = scanFeatures(exs);	// pass in the caching version
		params.featIdx.stopGrowth();
		
		// setup model and train
		int numParams = params.featIdx.size() + 1;
		CrfTrainer trainer = new CrfTrainer(trainerParams);
		params.model = new FgModel(numParams);
		trainerParams.regularizer = getRegularizer(numParams, regularizerMult);
		try {
			params.model = trainer.train(params.model, exs);
		}
		catch(cc.mallet.optimize.OptimizationException oe) {
			oe.printStackTrace();
		}
		System.out.printf("[train] done training on %d examples for %.1f seconds\n", exs.size(), (System.currentTimeMillis()-start)/1000d);
		System.out.println("[train] params.featIdx.size = " + params.featIdx.size());
	}
	
	
	public List<FNParse> parseWithoutPeeking(List<FNParse> raw) {
		return parse(DataUtil.stripAnnotations(raw));
	}
	public List<FNParse> parse(List<Sentence> raw) {
		FgInferencerFactory infFact = infFactory();
		List<FNParse> pred = new ArrayList<FNParse>();
		for(Sentence s : raw) {
			if(params.mode == Mode.FRAME_ID)
				pred.add(new FrameIdSentence(s, params).decode(params.model, infFact));
			else if(params.mode == Mode.JOINT_FRAME_ARG)
				pred.add(new JointFrameRoleIdSentence(s, params).decode(params.model, infFact));
			else if(params.mode == Mode.PIPELINE_FRAME_ARG) {
				FNTagging predictedFrames = new FrameIdSentence(s, params).decode(params.model, infFact);
				pred.add(new RoleIdSentence(s, predictedFrames, params).decode(params.model, infFact));
			}
			else throw new RuntimeException();
		}
		return pred;
	}
	
	
	/**
	 * mode=FRAME_ID: will tune params.frameDecoder.recallBias
	 * mode=PIPELINE: will tune recallBias for params.frameDecoder first, then params.argDecoder
	 * mode=JOINT: will do a sweep over recallBias for (frameDecoder x argDecoder)
	 */
	public void tune(List<FNParse> examples) { tune(examples, examples.size()); }
	public void tune(List<FNParse> examples, int maxExamples) {
		
		if(examples.size() > maxExamples) {
			System.out.printf("[Parser tune] only using %d of %d examples\n", maxExamples, examples.size());
			examples = DataUtil.reservoirSample(examples, maxExamples);
		}
		
		Timer t = Timer.start("tune");
		switch(params.mode) {
		case FRAME_ID:

			EvalFunc obj = BasicEvaluation.targetMicroF1;

			List<Double> biases = new ArrayList<Double>();
			for(double b=0.4d; b<7d; b*=1.3d) biases.add(b);

			double bestScore = Double.NEGATIVE_INFINITY;
			List<Double> scores = new ArrayList<Double>();
			for(double b : biases) {
				params.frameDecoder.setRecallBias(b);
				List<FNParse> predicted = this.parseWithoutPeeking(examples);
				List<SentenceEval> instances = BasicEvaluation.zip(examples, predicted);
				double score = BasicEvaluation.targetMicroF1.evaluate(instances);
				System.out.printf("[Parser tune FRAME_ID] recallBias=%.2f %s=%.3f\n", b, obj.getName(), score);
				scores.add(score);
				if(score > bestScore) bestScore = score;
			}

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
			double bias = n / z;
			System.out.printf("[Parser tune FRAME_ID] took %.1f sec, done. recallBias %.2f => %.2f\n",
				t.totalTimeInSec(), params.frameDecoder.getRecallBias(), bias);
			params.frameDecoder.setRecallBias(bias);

			break;

		case PIPELINE_FRAME_ARG:
			System.err.println("[Parser tune PIPELINE] not tuning because i need to implement this TODO");
			break;
		case JOINT_FRAME_ARG:
			throw new RuntimeException("implement me");
		default:
			throw new RuntimeException("unknown mode: " + params.mode);
		}
	}
	

	/**
	 * writes out weights in human readable form
	 */
	public void writeWeights(File f) {
		System.out.println("[writeoutWeights] to " + f.getPath());
		if(params.model == null)
			throw new IllegalStateException();
		long start = System.currentTimeMillis();
		try {
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
			int n = params.model.getNumParams();	// overestimate
			assert n >= params.featIdx.size();
			double[] outParams = new double[n];
			params.model.updateDoublesFromModel(outParams);
			for(int i=0; i<params.featIdx.size(); i++)
				w.write(String.format("%f\t%s\n", outParams[i], params.featIdx.lookupObject(i)));
			w.close();
			System.out.printf("[writeoutWeights] done in %.1f seconds\n", (System.currentTimeMillis() - start)/1000d);
		}
		catch(Exception e) { throw new RuntimeException(e); }
	}
	
	/**
	 * uses java serialization to save everything in {@code this.params}.
	 * inverse of this method is the constructor that takes a file.
	 */
	public void writeModel(File f) {
		System.out.println("[writeModel] to " + f.getPath());
		try {
			OutputStream os = new FileOutputStream(f);
			if(f.getName().toLowerCase().endsWith(".gz"))
				os = new GZIPOutputStream(os);
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(params);
			oos.close();
		}
		catch(Exception e) { throw new RuntimeException(e); }
	}
	
}
