package edu.jhu.hlt.fnparse.inference.newstuff;

import static edu.jhu.hlt.fnparse.util.ScalaLike.*;

import java.util.List;

import edu.jhu.gm.data.FgExampleMemoryStore;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;

public class Parser {

	public void train(List<FNParse> examples) {
		
		CrfTrainer.CrfTrainerPrm trainerPrm = new CrfTrainer.CrfTrainerPrm();
		CrfTrainer trainer = new CrfTrainer(trainerPrm);
		
		FgExampleMemoryStore exs = new FgExampleMemoryStore();
		for(FNParse parse : examples) {
			NewParsingSentence s = new NewParsingSentence(parse.getSentence());
			s.setGold(parse);
			exs.add(s.getFgExample());
		}
		
		// TODO: Feature name tracking is done by me.
		int numParams = 1000;
		FgModel model = new FgModel(numParams);
		model = trainer.train(model, exs);
	}

	public static void main(String[] args) {
		
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
	}
}
