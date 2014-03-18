package edu.jhu.hlt.fnparse.inference.newstuff;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.data.FgExampleCache;
import edu.jhu.gm.data.FgExampleList;
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
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.BasicFrameRoleFeatures;
import edu.jhu.hlt.fnparse.features.BasicRoleSpanFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingFrameFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingFrameRoleFeatures;
import edu.jhu.hlt.fnparse.features.DebuggingRoleSpanFeatures;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.features.caching.RawExampleFactory;
import edu.jhu.hlt.fnparse.inference.heads.BraindeadHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.pruning.ArgPruner;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.util.Avg;
import edu.jhu.optimize.AdaGrad;
import edu.jhu.optimize.HeterogeneousL2;
import edu.jhu.optimize.Regularizer;
import edu.jhu.optimize.SGD;
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
		public List<FactorFactory> factors;
		public TargetPruningData targetPruningData;
		public ArgPruner argPruner;
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

		params.headFinder = new BraindeadHeadFinder();	// TODO
		params.frameDecoder = new ApproxF1MbrDecoder(1d);
		params.argDecoder = new ApproxF1MbrDecoder(1.5d);
		params.argPruner = new ArgPruner(params);		// TODO fix this, should pass in required data
		
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
			
			// only frame id (no args)
			s.setGold(p, false);
			ParsingSentence.FgExample e1 = s.getFgExample();
			
			// clamped frames, predict args
			s = new ParsingSentence(p.getSentence(), params);
			s.setGold(p, false);
			s.decodeFrames(params.model, this.infFactory());
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
		CrfTrainer trainer = new CrfTrainer(trainerParams);
		if(params.model == null)
			params.model = new FgModel(numParams);
		trainerParams.regularizer = getRegularizer(numParams, regularizerMult);
		
		
		int keepInMemory = params.mode == Mode.FRAME_ID ? 15000 : 10;
		
		RawExampleFactory rexs = new RawExampleFactory(examples, this);
		FgExampleList exs = new FgExampleCache(rexs, keepInMemory, false);
		
		if(params.debug)
			rexs.setTimerPrintInterval(params.mode == Mode.FRAME_ID ? 500 : (params.mode == Mode.PIPELINE_FRAME_ARG ? 10 : 1));
		
		try { params.model = trainer.train(params.model, exs); }
		catch(cc.mallet.optimize.OptimizationException oe) {
			oe.printStackTrace();
		}
		params.featIdx.stopGrowth();
		System.out.printf("[train] done training on %d examples for %.1f seconds\n", exs.size(), (System.currentTimeMillis()-start)/1000d);
	}
	
	public void computeStatistcs(List<FNParse> examples) {
		Avg macroTargetRecall = new Avg();
		Avg microTargetRecall = new Avg();
		Avg framesPerTarget = new Avg();
		Avg targetsPerSent = new Avg();
		Avg rolesPerFrameVar = new Avg();
		
		for(FNParse p : examples) {
			Sentence s = p.getSentence();
			ParsingSentence ps = new ParsingSentence(s, params);
			double tr = ps.computeMaxTargetRecall(p);
			macroTargetRecall.accum(tr);
			microTargetRecall.accum(tr, p.getFrameInstances().size());
			int ts = 0;
			for(int i=0; i<s.size(); i++) {
				FrameVar fv = ps.frameVars[i];
				if(fv == null) continue;
				ts++;
				framesPerTarget.accum(fv.getFrames().size());
				rolesPerFrameVar.accum(fv.getMaxRoles());
			}
			targetsPerSent.accum(ts);
		}

		System.out.printf("[computeStatistcs] upper bound on target recall (due to heuristics) = %.1f/%.1f (micro/macro)\n",
				100d*microTargetRecall.average(), 100d*macroTargetRecall.average());
		System.out.printf("[computeStatistcs] frames/target=%.2f targets/sent=%.2f total-#targets=%d roles/frame=%.1f\n",
				framesPerTarget.average(), targetsPerSent.average(), (int) targetsPerSent.numerator(), rolesPerFrameVar.average());
	}
	
	
	public List<FNParse> parseWithoutPeeking(List<FNParse> raw) {
		return parse(DataUtil.stripAnnotations(raw));
	}
	public List<FNParse> parse(List<Sentence> raw) {
		FgInferencerFactory infFact = this.infFactory();
		List<FNParse> pred = new ArrayList<FNParse>();
		for(Sentence s : raw) {
			ParsingSentence ps = new ParsingSentence(s, params);
			FNTagging onlyTargets = ps.decodeFrames(params.model, infFact);
			if(params.mode == Mode.FRAME_ID)
				pred.add(new FNParse(s, onlyTargets.getFrameInstances()));
			else
				pred.add(ps.decodeArgs(params.model, infFact));
		}
		return pred;
	}
	
	
	public ParserParams getParams() { return params; }

	/**
	 * writes out weights in human readable form
	 */
	public void writeWeights(File f) {
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
	
	/**
	 * uses java serialization to save everything in {@code this.params}
	 */
	public void writeModel(File f) {
		System.out.println("[writeModel] to " + f.getPath());
		try {
			ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f));
			oos.writeObject(params);
			oos.close();
		}
		catch(Exception e) { throw new RuntimeException(e); }
	}
	
	/**
	 * uses java serialization to read everything in to fill {@code this.params}
	 */
	public void readModel(File f) {
		System.out.println("[readModel] to " + f.getPath());
		try {
			ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f));
			params = (ParserParams) ois.readObject();
			ois.close();
		}
		catch(Exception e) { throw new RuntimeException(e); }
	}
	
}
