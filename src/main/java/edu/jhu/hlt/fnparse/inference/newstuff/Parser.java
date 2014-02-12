package edu.jhu.hlt.fnparse.inference.newstuff;

import static edu.jhu.hlt.fnparse.util.ScalaLike.println;

import java.io.*;
import java.util.*;

import edu.jhu.gm.data.FgExampleMemoryStore;
import edu.jhu.gm.inf.BeliefPropagation.BeliefPropagationPrm;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.indexing.BasicBob;
import edu.jhu.hlt.fnparse.features.indexing.SuperBob;
import edu.jhu.optimize.AdaGrad;
import edu.jhu.optimize.Function;
import edu.jhu.optimize.Maximizer;
import edu.jhu.optimize.SGD;

public class Parser {

	static class ParserParams {
		public boolean logDomain;
		public FgModel model;
		public List<FactorFactory> factors;
		public FrameIndex frameIndex;
		public Map<Frame, List<FrameInstance>> prototypes;

		public CrfTrainer trainer;
		public CrfTrainer.CrfTrainerPrm trainerParams;
	}
	
	private ParserParams params;
	
	public Parser() {
		params = new ParserParams();
		params.logDomain = true;		// doesn't work if this is false :(
		params.frameIndex = FrameIndex.getInstance();
		
		params.factors = new ArrayList<FactorFactory>();
		params.factors.add(new Factors.FramePrototypeFactors());
		params.factors.add(new Factors.FrameFactors());
		params.factors.add(new Factors.FrameRoleFactors());
		params.factors.add(new Factors.FrameExpansionFactors());
		params.factors.add(new Factors.ArgExpansionFactors());
		
		params.prototypes = params.frameIndex.getPrototypeMap();
	}
	

	public void train(List<FNParse> examples) {
		
		BasicBob bob = (BasicBob) SuperBob.getBob(null, BasicBob.NAME);
		
		CrfTrainer.CrfTrainerPrm trainerParams = new CrfTrainer.CrfTrainerPrm();
		
		SGD.SGDPrm sgdParams = new SGD.SGDPrm();
		sgdParams.batchSize = 1;
		//sgdParams.initialLr = 0.1d;	// adagrad ignores this
		sgdParams.numPasses = 10;
		AdaGrad.AdaGradPrm adagParams = new AdaGrad.AdaGradPrm();
		adagParams.sgdPrm = sgdParams;
		adagParams.eta = 0.1d;
		trainerParams.batchMaximizer = new AdaGrad(adagParams);
		
		BeliefPropagationPrm bpParams = new BeliefPropagationPrm();
		bpParams.normalizeMessages = true;	// doesn't work if false :(
		bpParams.logDomain = params.logDomain;
		trainerParams.infFactory = bpParams;
		//trainerPrm.numThreads = 4;
		
		int numParams;
		if(bob.isFirstPass()) {
			System.out.println("[train] this is the first pass, need to compute feature widths, not doing learning...");
			numParams = 125000;	// hope this is enough
			trainerParams.maximizer = new Maximizer() {
				@Override
				public boolean maximize(Function function, double[] point) { return true; }
			};
			trainerParams.batchMaximizer = null;
			trainerParams.regularizer = null;
		}
		else {
			trainerParams.maximizer = null;
			numParams = bob.totalFeatures();
			System.out.println("[train] #features = " + bob.totalFeatures() + ", optimizing with " + trainerParams.batchMaximizer);
			assert trainerParams.batchMaximizer != null;
		}

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
	}
	
	
	public List<FNParse> parse(List<Sentence> raw) {
		List<FNParse> pred = new ArrayList<FNParse>();
		for(Sentence s : raw) {
			ParsingSentence ps = new ParsingSentence(s, params);
			pred.add(ps.decode(params.model));
		}
		return pred;
	}
	
	
	public ParserParams getParams() { return params; }

	
	public void writeoutWeights(File f) {
		System.out.println("[writeoutWeights] to " + f.getPath());
		try {
			//File f = new File("weights.txt");
			BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f)));
			BasicBob b = (BasicBob) SuperBob.getBob(null, BasicBob.NAME);
			String[] fnNames = b.getFeatureNames();
			double[] outParams = new double[params.model.getNumParams()];
			if(fnNames.length != outParams.length) {
				System.out.println("wtuf");
			}
			params.model.updateDoublesFromModel(outParams);
			for(int i=0; i<outParams.length; i++)
				w.write(outParams[i] + "\t" + fnNames[i] + "\n");
			w.close();
		}
		catch(Exception e) { throw new RuntimeException(e); }
	}
	
	public static void main(String[] args) {
		
		System.setProperty(SuperBob.WHICH_BOB, "BasicBob");
		System.setProperty(BasicBob.BASIC_BOBS_FILE, "feature-widths.txt");
		SuperBob.getBob(null).startup();
		
		FrameInstanceProvider fip = FileFrameInstanceProvider.fn15trainFIP;
		List<FNParse> all = fip.getParsedSentences();
		println("all.size = " + all.size());
		int trainOn = 1;
		List<FNParse> sample = DataUtil.reservoirSample(all, trainOn);
		println("training on " + trainOn + " sentences...");
		Parser p = new Parser();
		
		long start = System.currentTimeMillis();
		p.train(sample);
		System.out.printf("training took %.1f seconds for %d examples\n", (System.currentTimeMillis()-start)/1000d, trainOn);
		
		SuperBob.getBob(null).shutdown();
	}
}
