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
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.BasicRoleDepFeatures;
import edu.jhu.hlt.fnparse.features.BasicRoleFeatures;
import edu.jhu.hlt.fnparse.features.BasicRoleSpanFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingFrameDepFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingFrameFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingRoleFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingRoleSpanFeatures;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.features.caching.RawExampleFactory;
import edu.jhu.hlt.fnparse.inference.frameid.FrameFactorFactory;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdSentence;
import edu.jhu.hlt.fnparse.inference.frameid.FrameVars;
import edu.jhu.hlt.fnparse.inference.heads.BraindeadHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
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
		
		public Mode mode;
		public Alphabet<String> featIdx;
		public FgModel model;
		public HeadFinder headFinder;
		public ApproxF1MbrDecoder frameDecoder;
		public ApproxF1MbrDecoder argDecoder;
		public TargetPruningData targetPruningData;
		public IArgPruner argPruner;
		
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
			System.out.printf("[Parser] done reading model in %.1f seconds\n", (System.currentTimeMillis() - start)/1000d);
		}
		catch(Exception e) { throw new RuntimeException(e); }
	}
	
	public Parser(Mode mode, boolean latentDeps, boolean debug) {

		params = new ParserParams();
		params.debug = debug;
		params.featIdx = new Alphabet<String>();
		params.logDomain = false;
		params.useLatentDepenencies = latentDeps;
		params.mode = mode;
		params.usePrototypes = false;
		params.useSyntaxFeatures = true;
		params.fastFeatNames = debug;
		params.targetPruningData = TargetPruningData.getInstance();

		params.headFinder = new BraindeadHeadFinder();	// TODO
		params.frameDecoder = new ApproxF1MbrDecoder(params.logDomain, 1d);
		params.argDecoder = new ApproxF1MbrDecoder(params.logDomain, 1.5d);
		params.argPruner = new ArgPruner(params);
		
		params.factorsForFrameId = new FrameFactorFactory(params, latentDeps, latentDeps);
		params.factorsForRoleId = new RoleFactorFactory(params, latentDeps, latentDeps, false);
		params.factorsForJointId = new JointFactorFactory(params);
		
		if(params.debug) {
			params.fFeatures = new DebuggingFrameFeatures(params.featIdx);
			params.fdFeatures = new DebuggingFrameDepFeatures(params.featIdx);
			params.rFeatures = new DebuggingRoleFeatures(params.featIdx);
			params.rdFeatures = new BasicRoleDepFeatures(params.featIdx);
			params.reFeatures = new DebuggingRoleSpanFeatures(params.featIdx);
		}
		else {
			params.fFeatures = new BasicFrameFeatures(params);
			params.fdFeatures = new DebuggingFrameDepFeatures(params.featIdx);
			params.rFeatures = new BasicRoleFeatures(params);
			params.rdFeatures = new BasicRoleDepFeatures(params.featIdx);
			params.reFeatures = new BasicRoleSpanFeatures(params);
		}
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
			
			// TODO once i'm loading frameId models from disk,
			// this should only return one example, which is a roleId example
			
			
			// only frame id (no args)
			FrameIdSentence fid = new FrameIdSentence(p.getSentence(), params, p);
			LabeledFgExample e1 = fid.getTrainingExample();
			
			// run prediction to see what frames we'll be predicting roles for
			//FNTagging predictedFrames = fid.decode(params.model, infFactory());
			/*
			 * TODO i need to split out this training into stages where i train the frame id
			 * and role id separately (role id depends on frame id). for now i'm just going to
			 * train the role id as if the frame id system were perfect. this is not ideal because
			 * it will lead to lower precision than is necessary (i.e. if you get the frame wrong,
			 * you should train the model to not predict roles).
			 */
			FNTagging predictedFrames = p;
			
			// clamped frames, predict args
			RoleIdSentence argId = new RoleIdSentence(p.getSentence(), predictedFrames, params, p);
			LabeledFgExample e2 = argId.getTrainingExample();
			
			return Arrays.asList(e1, e2);
		}
		else throw new RuntimeException();
	}

	public void train(List<FNParse> examples) { train(examples, 10, 5, 1d, 1d); }
	public void train(List<FNParse> examples, int passes, int batchSize, double learningRateMultiplier, double regularizerMult) {
		
		System.out.println("[Parser train] starting training in " + params.mode + " mode...");
		params.featIdx.startGrowth();
		Logger.getLogger(CrfTrainer.class).setLevel(Level.ALL);
		long start = System.currentTimeMillis();
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
		
		int numParams = params.debug
				? 750 * 1000
				: 4 * 1000 * 1000;	// TODO
		CrfTrainer trainer = new CrfTrainer(trainerParams);
		if(params.model == null)
			params.model = new FgModel(numParams);
		trainerParams.regularizer = getRegularizer(numParams, regularizerMult);
		
		int keepInMemory = params.mode == Mode.FRAME_ID ? 15000 : 10;
		
		RawExampleFactory rexs = new RawExampleFactory(examples, this);
		FgExampleList exs = new FgExampleCache(rexs, keepInMemory, false);
		
		if(params.debug || true) {
			int lim = params.mode == Mode.FRAME_ID
					? 150
					: (params.mode == Mode.PIPELINE_FRAME_ARG ? 10 : 1);
			rexs.setTimerPrintInterval(lim);
		}
		
		try { params.model = trainer.train(params.model, exs); }
		catch(cc.mallet.optimize.OptimizationException oe) {
			oe.printStackTrace();
		}
		params.featIdx.stopGrowth();
		System.out.printf("[train] done training on %d examples for %.1f seconds\n", exs.size(), (System.currentTimeMillis()-start)/1000d);
		System.out.printf("[train] used %d strings in feature name alphabet\n", params.featIdx.size());
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
			StdEvalFunc obj = BasicEvaluation.targetMicroF1;
			double origRecallBias = params.frameDecoder.getRecallBias();
			double bestRecallBias = origRecallBias;
			double bestScore = Double.NEGATIVE_INFINITY;
			for(double recallBias = 0.5d; recallBias < 6d; recallBias *= 1.5d) {
				params.frameDecoder.setRecallBias(recallBias);
				List<FNParse> predicted = this.parseWithoutPeeking(examples);
				List<SentenceEval> instances = BasicEvaluation.zip(examples, predicted);
				double score = BasicEvaluation.targetMicroF1.evaluate(instances);
				System.out.printf("[Parser tune FRAME_ID] recallBias=%.2f %s=%.3f\n", recallBias, obj.getName(), score);
				if(score > bestScore) {
					bestRecallBias = recallBias;
					bestScore = score;
				}
			}
			System.out.printf("[Parser tune FRAME_ID] took %.1f sec, done. recallBias %.2f => %.2f\n", t.totalTimeInSec(), origRecallBias, bestRecallBias);
			params.frameDecoder.setRecallBias(bestRecallBias);
			break;
		case PIPELINE_FRAME_ARG:
			throw new RuntimeException("implement me");
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
