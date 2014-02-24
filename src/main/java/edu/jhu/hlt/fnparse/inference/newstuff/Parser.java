package edu.jhu.hlt.fnparse.inference.newstuff;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import edu.jhu.gm.data.FgExampleMemoryStore;
import edu.jhu.gm.inf.BeliefPropagation.BeliefPropagationPrm;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.features.*;
import edu.jhu.optimize.*;
import edu.jhu.util.Alphabet;

public class Parser {
	
	public static class ParserParams {
		public boolean logDomain;
		public boolean useLatentDepenencies;
		
		public Alphabet<String> featIdx;
		public FgModel model;
		public List<FactorFactory> factors;
		public FrameIndex frameIndex;
		public Map<Frame, List<FrameInstance>> prototypes;

		public CrfTrainer trainer;
		public CrfTrainer.CrfTrainerPrm trainerParams;
	}
	
	private ParserParams params;
	
	// TODO
	// the reason why what i'm doing is wrong (MLE training + marginal frame decode + clamping)
	// is that you are maximizing likelihood of the correct arguments for the incorrect frame assignments.
	// ideally you would train just like you decode, which would mean training to predict r_ijk=nullSpan when you get the frames wrong.
	
	public Parser() {
		params = new ParserParams();
		params.featIdx = new Alphabet<String>();
		params.logDomain = false;
		params.frameIndex = FrameIndex.getInstance();
		params.useLatentDepenencies = false;
		params.factors = new ArrayList<FactorFactory>();
		FrameFactorFactory fff = new FrameFactorFactory();
		fff.setFeatures(new BasicFrameFeatures(params.featIdx));
		fff.setFeatures(new DebuggingConstituencyFeatures(params.featIdx));
		fff.setFeatures(new BasicFramePrototypeFeatures(params.featIdx));
		params.factors.add(fff);
		RoleFactorFactory rff = new RoleFactorFactory(params);
		rff.setFeatures(new BasicFrameRoleFeatures(params.featIdx));
//		rff.setFeatures(new DebuggingFrameRoleFeatures(params.featIdx));
		params.factors.add(rff);
		params.prototypes = params.frameIndex.getPrototypeMap();
	}
	
	public FgInferencerFactory infFactory() {
		final BeliefPropagationPrm bpParams = new BeliefPropagationPrm();
		bpParams.normalizeMessages = true;	// doesn't work if false :(
		bpParams.logDomain = params.logDomain;
		bpParams.cacheFactorBeliefs = false;
		bpParams.maxIterations = 2;	// similar to piecewise training
		return new FgInferencerFactory() {
			@Override
			public boolean isLogDomain() { return bpParams.isLogDomain(); }
			@Override
			public FgInferencer getInferencer(FactorGraph fg) {
				return new BenchmarkingBP(fg, bpParams);
			}
		};
	}
	

	public void train(List<FNParse> examples) {
		
//		BasicBob bob = (BasicBob) SuperBob.getBob(null, BasicBob.NAME);
		long start = System.currentTimeMillis();
		CrfTrainer.CrfTrainerPrm trainerParams = new CrfTrainer.CrfTrainerPrm();
		
		SGD.SGDPrm sgdParams = new SGD.SGDPrm();
		sgdParams.batchSize = 1;
		//sgdParams.initialLr = 0.1d;	// adagrad ignores this
		sgdParams.numPasses = 10;
		AdaGrad.AdaGradPrm adagParams = new AdaGrad.AdaGradPrm();
		adagParams.sgdPrm = sgdParams;
		adagParams.eta = 0.1d;
		trainerParams.maximizer = null;
		trainerParams.batchMaximizer = new AdaGrad(adagParams);
		trainerParams.infFactory = infFactory();
		
		// L2's parameter is variance => bigger means less regularization
		// L1's parameter is multiplier => bigger means more regularization
		trainerParams.regularizer = new L1(1d);	//new L2(1d);
		
//		int numParams;
//		if(bob.isFirstPass()) {
//			System.out.println("[train] this is the first pass, need to compute feature widths, not doing learning...");
//			numParams = 125000;	// hope this is enough
//			trainerParams.maximizer = new Maximizer() {
//				boolean onceThrough = false;
//				@Override
//				public boolean maximize(Function function, double[] point) {
//					if(onceThrough) return true;
//					onceThrough = true;
//					return false;
//				}
//			};
//			trainerParams.batchMaximizer = null;
//			trainerParams.regularizer = null;
//		}
//		else {
//			trainerParams.maximizer = null;
//			numParams = bob.totalFeatures();
//			System.out.println("[train] #features = " + bob.totalFeatures() + ", optimizing with " + trainerParams.batchMaximizer);
//			assert trainerParams.batchMaximizer != null;
//		}

		int numParams = 250000;	// TODO
		params.trainerParams = trainerParams;
		params.trainer = new CrfTrainer(trainerParams);
		params.model = new FgModel(numParams);
		
		FgExampleMemoryStore exs = new FgExampleMemoryStore();
		for(FNParse parse : examples) {
			ParsingSentence s = new ParsingSentence(parse.getSentence(), params);
			s.setGold(parse);
			exs.add(s.getFgExample());
		}
		
		try { params.model = params.trainer.train(params.model, exs); }
		catch(cc.mallet.optimize.OptimizationException oe) {
			oe.printStackTrace();
		}
		System.out.printf("[train] done training on %d examples for %.1f seconds\n", exs.size(), (System.currentTimeMillis()-start)/1000d);
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
			//File f = new File("weights.txt");
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
//			BasicBob b = (BasicBob) SuperBob.getBob(null, BasicBob.NAME);
//			String[] fnNames = b.getFeatureNames();
			int n = params.model.getNumParams();	// overestimate
			assert n >= params.featIdx.size();
			double[] outParams = new double[n];
			params.model.updateDoublesFromModel(outParams);
			for(int i=0; i<params.featIdx.size(); i++) {
//				w.write(outParams[i] + "\t" + fnNames[i] + "\n");
				w.write(String.format("%f\t%s\n", outParams[i], params.featIdx.lookupObject(i)));
			}
			w.close();
		}
		catch(Exception e) { throw new RuntimeException(e); }
	}
	
//	public static void main(String[] args) {
//		
//		System.setProperty(SuperBob.WHICH_BOB, "BasicBob");
//		System.setProperty(BasicBob.BASIC_BOBS_FILE, "feature-widths.txt");
//		SuperBob.getBob(null).startup();
//		
//		FrameInstanceProvider fip = FileFrameInstanceProvider.fn15trainFIP;
//		List<FNParse> all = fip.getParsedSentences();
//		println("all.size = " + all.size());
//		int trainOn = 1;
//		List<FNParse> sample = DataUtil.reservoirSample(all, trainOn);
//		println("training on " + trainOn + " sentences...");
//		Parser p = new Parser();
//		
//		long start = System.currentTimeMillis();
//		p.train(sample);
//		System.out.printf("training took %.1f seconds for %d examples\n", (System.currentTimeMillis()-start)/1000d, trainOn);
//		
//		SuperBob.getBob(null).shutdown();
//	}
}