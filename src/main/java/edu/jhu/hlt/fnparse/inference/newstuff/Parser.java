package edu.jhu.hlt.fnparse.inference.newstuff;

import static edu.jhu.hlt.fnparse.util.ScalaLike.println;

import java.util.List;

import edu.jhu.gm.data.FgExampleMemoryStore;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.indexing.BasicBob;
import edu.jhu.hlt.fnparse.indexing.SuperBob;
import edu.jhu.optimize.Function;
import edu.jhu.optimize.Maximizer;

public class Parser {

	public void train(List<FNParse> examples) {
		
		CrfTrainer.CrfTrainerPrm trainerPrm = new CrfTrainer.CrfTrainerPrm();
		//trainerPrm.numThreads = 4;
		CrfTrainer trainer = new CrfTrainer(trainerPrm);
		
		FgExampleMemoryStore exs = new FgExampleMemoryStore();
		for(FNParse parse : examples) {
			NewParsingSentence s = new NewParsingSentence(parse.getSentence());
			s.setGold(parse);
			exs.add(s.getFgExample());
		}
		
		BasicBob bob = (BasicBob) SuperBob.getBob(null, BasicBob.NAME);
		int numParams;
		if(bob.isFirstPass()) {
			numParams = 25000;
			
			// doesn't work...
			trainerPrm.maximizer = new Maximizer() {
				@Override
				public boolean maximize(Function function, double[] point) { return true; }
			};
			trainerPrm.regularizer = null;
			
//			MalletLBFGSPrm m = new MalletLBFGSPrm();
//			m.maxIterations = 1;
//			m.tolerance = 1d;
//			trainerPrm.maximizer = new MalletLBFGS(m);
//			
//			trainerPrm.maximizer = null;
		}
		else {
			numParams = bob.totalFeatures();
			System.out.println("#features = " + bob.totalFeatures());
		}
		FgModel model = new FgModel(numParams);
		model = trainer.train(model, exs);
	}

	public static void main(String[] args) {
		
		System.setProperty(SuperBob.WHICH_BOB, "BasicBob");
		System.setProperty(BasicBob.BASIC_BOBS_FILE, "feature-widths.txt");
		SuperBob.getBob(null).startup();
		
		FrameInstanceProvider fip = new FNFrameInstanceProvider();
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
