package edu.jhu.hlt.fnparse.inference.newstuff;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleCache;
import edu.jhu.gm.data.FgExampleList;
import edu.jhu.gm.data.FgExampleMemoryStore;
import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.inf.BeliefPropagation.BeliefPropagationPrm;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.BasicFrameRoleFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingFrameFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingFrameRoleFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingRoleSpanFeatures;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.features.RoleConstituencyFeatures;
import edu.jhu.hlt.fnparse.features.caching.RawExampleFactory;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.util.Avg;
import edu.jhu.hlt.fnparse.util.HeterogeneousL2;
import edu.jhu.optimize.AdaGrad;
import edu.jhu.optimize.Regularizer;
import edu.jhu.optimize.SGD;
import edu.jhu.util.Alphabet;

public class Parser {
	
	public static class ParserParams implements Serializable {
		public boolean debug;
		public boolean logDomain;
		public boolean useLatentDepenencies;
		public boolean onlyFrameIdent;
		public boolean usePrototypes;
		
		public Alphabet<String> featIdx;
		public FgModel model;
		public ApproxF1MbrDecoder frameDecoder;
		public ApproxF1MbrDecoder argDecoder;
		public List<FactorFactory> factors;
		public FrameIndex frameIndex;
		public TargetPruningData targetPruningData;

		public transient CrfTrainer trainer;
		public transient CrfTrainer.CrfTrainerPrm trainerParams;
	}
	
	
	public ParserParams params;
	public final boolean benchmarkBP = false;
	
	public Parser() {
		this(false);
	}
	
	public Parser(boolean debug) {

		params = new ParserParams();
		params.debug = debug;
		params.featIdx = new Alphabet<String>();
		params.logDomain = false;
		params.frameIndex = FrameIndex.getInstance();
		params.useLatentDepenencies = false;
		params.onlyFrameIdent = false;
		params.usePrototypes = false;
		params.targetPruningData = TargetPruningData.getInstance();

		params.frameDecoder = new ApproxF1MbrDecoder(1d);
		params.argDecoder = new ApproxF1MbrDecoder(1.5d);
		
		params.factors = new ArrayList<FactorFactory>();
		FrameFactorFactory fff = new FrameFactorFactory();
		if(params.debug) fff.setFeatures(new DebuggingFrameFeatures(params.featIdx));
		else {
			fff.setFeatures(new BasicFrameFeatures(params.featIdx));
			//fff.setFeatures(new BasicFramePrototypeFeatures(params.featIdx));
		}
		params.factors.add(fff);
		
		if(!params.onlyFrameIdent) {
			RoleFactorFactory rff = new RoleFactorFactory(params);
			if(params.debug) {
				rff.setFeatures(new DebuggingRoleSpanFeatures(params.featIdx));
				rff.setFeatures(new DebuggingFrameRoleFeatures(params.featIdx));
			}
			else {
				rff.setFeatures(new RoleConstituencyFeatures(params.featIdx));
				rff.setFeatures(new BasicFrameRoleFeatures(params.featIdx));
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
	
	public ParsingSentence getSentenceForTraining(FNParse p) {
		ParsingSentence s = new ParsingSentence(p.getSentence(), params);
		s.setupRoleVarsForTrain(p);
		return s;
	}
	
	public FgExample getExampleForTraining(FNParse p, boolean initFeatures) {
		FgExample fge = getSentenceForTraining(p).getFgExample();
		if(initFeatures) {
			double cantGetThisWithoutFeatures = 0d;
			FactorGraph fg = fge.getFgLatPred();
			for(Factor f : fg.getFactors()) {
				// my jenk way to get features to be computed (might not work for other classes)
				int n = f.getVars().calcNumConfigs();
				for(int i=0; i<n; i++)
					cantGetThisWithoutFeatures += f.getUnormalizedScore(i);
			}
		}
		return fge;
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
				: 50 * 1000 * 1000;	// TODO
		params.trainerParams = trainerParams;
		params.trainer = new CrfTrainer(trainerParams);
		if(params.model == null)
			params.model = new FgModel(numParams);
		trainerParams.regularizer = getRegularizer(numParams, regularizerMult);
		
		Avg macroTargetRecall = new Avg();
		Avg microTargetRecall = new Avg();
		Avg framesPerTarget = new Avg();
		Avg targetsPerSent = new Avg();
		
		//FgExampleMemoryStore exs = new FgExampleMemoryStore();
//		for(FNParse parse : examples) {
//			
//			ParsingSentence s = getSentenceForTraining(parse);
//			exs.add(s.getFgExample());
//
//			// compute upper bound on target recall
//			double recall = s.computeMaxTargetRecall(parse);
//			macroTargetRecall.accum(recall);
//			microTargetRecall.accum(recall, parse.numFrameInstances());
//			
//			int numVars = 0;
//			for(FrameVar fv : s.frameVars) {
//				if(fv == null) continue;
//				framesPerTarget.accum(fv.getFrames().size());
//				numVars++;
//			}
//			targetsPerSent.accum(numVars);
//		}
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
		List<FNParse> pred = new ArrayList<FNParse>();
		for(Sentence s : raw) {
			ParsingSentence ps = new ParsingSentence(s, params);
			pred.add(ps.decode(params.model, this.infFactory()));
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
