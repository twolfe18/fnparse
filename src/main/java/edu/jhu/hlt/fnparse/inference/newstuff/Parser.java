package edu.jhu.hlt.fnparse.inference.newstuff;

import static edu.jhu.hlt.fnparse.util.ScalaLike.println;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import edu.jhu.hlt.fnparse.features.indexing.BasicBob;
import edu.jhu.hlt.fnparse.features.indexing.SuperBob;
import edu.jhu.optimize.Function;
import edu.jhu.optimize.Maximizer;

public class Parser {

	static class ParserParams {
		public boolean logDomain;
		public FgModel model;
		public List<FactorFactory> factors;
		public FrameIndex frameIndex;
		public Map<Frame, List<FrameInstance>> prototypes;
		// features store alphabets
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
	}
	
	public void train(List<FNParse> examples) {
		
		CrfTrainer.CrfTrainerPrm trainerPrm = new CrfTrainer.CrfTrainerPrm();
		BeliefPropagationPrm bpParams = new BeliefPropagationPrm();
		bpParams.normalizeMessages = true;	// doesn't work if false :(
		bpParams.logDomain = params.logDomain;
		trainerPrm.infFactory = bpParams;
		//trainerPrm.numThreads = 4;
		CrfTrainer trainer = new CrfTrainer(trainerPrm);
		
		FgExampleMemoryStore exs = new FgExampleMemoryStore();
		for(FNParse parse : examples) {
			NewParsingSentence s = new NewParsingSentence(parse.getSentence(), params);
			s.setGold(parse);
			exs.add(s.getFgExample());
		}
		
		BasicBob bob = (BasicBob) SuperBob.getBob(null, BasicBob.NAME);
		int numParams;
		if(bob.isFirstPass()) {
			System.out.println("[train] this is the first pass, need to compute feature widths, not doing learning...");
			numParams = 25000;
			trainerPrm.maximizer = new Maximizer() {
				@Override
				public boolean maximize(Function function, double[] point) { return true; }
			};
			trainerPrm.regularizer = null;
		}
		else {
			numParams = bob.totalFeatures();
			System.out.println("#features = " + bob.totalFeatures());
		}
		params.model = new FgModel(numParams);
		params.model = trainer.train(params.model, exs);
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
