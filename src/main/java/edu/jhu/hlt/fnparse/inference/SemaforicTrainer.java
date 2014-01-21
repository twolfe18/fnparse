package edu.jhu.hlt.fnparse.inference;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;

public class SemaforicTrainer implements FrameNetParserTrainer {

	@Override
	public String getName() { return "SemaforicTrainer"; }

	@Override
	public FrameNetParser train(List<Sentence> examples) {
		Semaforic sema = new Semaforic();
		sema.train(examples, null, null);	// this will crash
		return sema;
	}

}
