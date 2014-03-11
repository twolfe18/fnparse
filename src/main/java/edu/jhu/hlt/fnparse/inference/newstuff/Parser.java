package edu.jhu.hlt.fnparse.inference.newstuff;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.data.*;
import edu.jhu.gm.inf.*;
import edu.jhu.gm.inf.BeliefPropagation.*;
import edu.jhu.gm.model.*;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.features.*;
import edu.jhu.hlt.fnparse.features.caching.RawExampleFactory;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.util.*;
import edu.jhu.optimize.*;
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
		public ApproxF1MbrDecoder frameDecoder;
		public ApproxF1MbrDecoder argDecoder;
		public List<FactorFactory> factors;
		public TargetPruningData targetPruningData;

		public transient CrfTrainer trainer;
		public transient CrfTrainer.CrfTrainerPrm trainerParams;
	}
	
	
	public ParserParams params;
	public final boolean benchmarkBP = false;
	
	public Parser() {
		this(Mode.JOINT_FRAME_ARG, false);
	}
	
	public Parser(Mode mode, boolean debug) {

		params = new ParserParams();
		params.debug = debug;
		params.featIdx = new Alphabet<String>();
		params.logDomain = false;
		params.useLatentDepenencies = false;
		params.mode = mode;
		params.usePrototypes = false;
		params.useSyntaxFeatures = true;
		params.fastFeatNames = debug;
		params.targetPruningData = TargetPruningData.getInstance();

		params.frameDecoder = new ApproxF1MbrDecoder(1d);
		params.argDecoder = new ApproxF1MbrDecoder(1.5d);
		
		params.factors = new ArrayList<FactorFactory>();
		FrameFactorFactory fff = new FrameFactorFactory();
		if(params.debug) fff.setFeatures(new DebuggingFrameFeatures(params.featIdx));
		else {
			fff.setFeatures(new BasicFrameFeatures(params));
			//fff.setFeatures(new BasicFramePrototypeFeatures(params.featIdx));
		}
		params.factors.add(fff);
		
		if(mode != Mode.FRAME_ID) {
			RoleFactorFactory rff = new RoleFactorFactory(params);
			if(params.debug) {
				rff.setFeatures(new DebuggingRoleSpanFeatures(params.featIdx));
				rff.setFeatures(new DebuggingFrameRoleFeatures(params.featIdx));
			}
			else {
				rff.setFeatures(new BasicRoleSpanFeatures(params));
				rff.setFeatures(new BasicFrameRoleFeatures(params));
			}
			params.factors.add(rff);
		}
	}
	
	public FgInferencerFactory infFactory() {
		final BeliefPropagationPrm bpParams = new BeliefPropagationPrm();
		bpParams.normalizeMessages = true;
		bpParams.logDomain = params.logDomain;
		bpParams.cacheFactorBeliefs = false;
		bpParams.maxIterations = 2;
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
		for(FactorFactory ff : this.params.factors)
			for(Features f : ff.getFeatures())
				dontRegularize.addAll(f.dontRegularize());
		System.out.printf("[getRegularizer] not regularizing %d parameters\n", dontRegularize.size());

		// L2's parameter is variance => bigger means less regularization
		// L1's parameter is multiplier => bigger means more regularization
		//return new L2(10d);
		return HeterogeneousL2.zeroMeanIgnoringIndices(dontRegularize, regularizerMult, numParams);
	}
	
	public List<ParsingSentence.FgExample> getExampleForTraining(FNParse p) {
		
		ParsingSentence s = new ParsingSentence(p.getSentence(), params);
		
		if(params.mode == Mode.FRAME_ID) {
			s.setGold(p, false);
			return Arrays.asList(s.getFgExample());
		}
		else if(params.mode == Mode.JOINT_FRAME_ARG) {
			s.setGold(p, false);
			s.setupRoleVars();
			return Arrays.asList(s.getFgExample());
		}
		else if(params.mode == Mode.PIPELINE_FRAME_ARG) {

			
			// DEBUG:
			// i'm running into the case where sharing data between two
			// examples is causing problems (specifically with the frameVars)
			
			// what should be the copying policy for variables and factors?
			// - it is nice to have state in the frameVars so that we get
			//   a) labels
			//   b) the set of possible frames
			// factor are easily copied (make a ExpFamFactor that stores its features, and no other information)
			// variables are also easy to copy
			// the problem is that the ancillary information for variables is not easy: which frames
			//   - this should be both 1) state in the frameVars and 2) only a value in FactorGraph
			
			// one solution is to have one ParsingSentence per training instance
			// for pipeline training, this will be two 
			// -> if we follow this, then the hard(er) case will be for PIPELINE training where we pass in a FNTagging
			//    ...not really. we just create the sentence as normal, and then do setGold(tagging, true)
			//    
			
			
			// only frame id (no args)
			s.setGold(p, false);
			ParsingSentence.FgExample e1 = s.getFgExample();
			
			// clamped frames, predict args
			s.setGold(p, true);
			s.setupRoleVars();
			ParsingSentence.FgExample e2 = s.getFgExample();
			
			return Arrays.asList(e1, e2);
		}
		else throw new RuntimeException();
	}

	public void train(List<FNParse> examples) { train(examples, 10, 1, 1d, 1d); }
	public void train(List<FNParse> examples, int passes, int batchSize, double learningRateMultiplier, double regularizerMult) {
		
		params.featIdx.startGrowth();
		Logger.getLogger(CrfTrainer.class).setLevel(Level.ALL);
		long start = System.currentTimeMillis();
		CrfTrainer.CrfTrainerPrm trainerParams = new CrfTrainer.CrfTrainerPrm();
		
		SGD.SGDPrm sgdParams = new SGD.SGDPrm();
		sgdParams.batchSize = batchSize;
		//sgdParams.initialLr = 0.1d;	// adagrad ignores this
		sgdParams.numPasses = passes;
		AdaGrad.AdaGradPrm adagParams = new AdaGrad.AdaGradPrm();
		adagParams.sgdPrm = sgdParams;
		adagParams.eta = 0.1d * learningRateMultiplier;
		trainerParams.maximizer = null;
		trainerParams.batchMaximizer = new AdaGrad(adagParams);
		trainerParams.infFactory = infFactory();
		
		int numParams = params.debug
				? 750 * 1000
				: 15 * 1000 * 1000;	// TODO
		params.trainerParams = trainerParams;
		params.trainer = new CrfTrainer(trainerParams);
		if(params.model == null)
			params.model = new FgModel(numParams);
		trainerParams.regularizer = getRegularizer(numParams, regularizerMult);
		
		Avg macroTargetRecall = new Avg();
		Avg microTargetRecall = new Avg();
		Avg framesPerTarget = new Avg();
		Avg targetsPerSent = new Avg();
		
		FgExampleList exs = new FgExampleCache(new RawExampleFactory(examples, this), 2, false);
		
		System.out.printf("[train] upper bound on target recall (due to heuristics) = %.1f/%.1f (micro/macro)\n",
				100d*microTargetRecall.average(), 100d*macroTargetRecall.average());
		System.out.printf("[train] frames/target=%.2f targets/sent=%.2f total-#targets=%d\n",
				framesPerTarget.average(), targetsPerSent.average(), (int) targetsPerSent.sum());
		
		try { params.model = params.trainer.train(params.model, exs); }
		catch(cc.mallet.optimize.OptimizationException oe) {
			oe.printStackTrace();
		}
		params.featIdx.stopGrowth();
		System.out.printf("[train] done training on %d examples for %.1f seconds\n", exs.size(), (System.currentTimeMillis()-start)/1000d);
	}
	
	
	public List<FNParse> parseWithoutPeeking(List<FNParse> raw) {
		return parse(DataUtil.stripAnnotations(raw));
	}
	public List<FNParse> parse(List<Sentence> raw) {
		FgInferencerFactory infFact = this.infFactory();
		List<FNParse> pred = new ArrayList<FNParse>();
		for(Sentence s : raw) {
			ParsingSentence ps = new ParsingSentence(s, params);
			ps.decodeFrames(params.model, infFact);
			pred.add(ps.decodeArgs(params.model, infFact));
		}
		return pred;
	}
	
	
	public ParserParams getParams() { return params; }

	
	public void writeoutWeights(File f) {
		System.out.println("[writeoutWeights] to " + f.getPath());
		try {
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
			int n = params.model.getNumParams();	// overestimate
			assert n >= params.featIdx.size();
			double[] outParams = new double[n];
			params.model.updateDoublesFromModel(outParams);
			for(int i=0; i<params.featIdx.size(); i++)
				w.write(String.format("%f\t%s\n", outParams[i], params.featIdx.lookupObject(i)));
			w.close();
		}
		catch(Exception e) { throw new RuntimeException(e); }
	}
	
}
