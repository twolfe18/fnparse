package edu.jhu.hlt.fnparse.features;

import java.io.File;
import java.util.List;
import java.util.Random;

import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.Parser.Mode;
import edu.jhu.hlt.fnparse.inference.ParsingSentence;
import edu.jhu.hlt.fnparse.util.Timer;

public class Benchmark {
	
	public static Parser.Mode mode = Mode.PIPELINE_FRAME_ARG;
	public static boolean latentDeps = false;

	public static void main(String[] args) {
		
		List<FNParse> parses = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		int n = 30;
		if(mode == Mode.FRAME_ID)
			n *= 10;
		parses = DataUtil.reservoirSample(parses, n, new Random(9001));
		
		//Parser p = new Parser(mode, latentDeps, false);
		Parser p = new Parser(new File("saved-models/alphabets/argId-reg.model.gz"));
		FgInferencerFactory infFact = p.infFactory();
		Timer t = new Timer("compute-features");
		t.ignoreFirstTime = true;
		t.setPrintInterval(1);
		for(FNParse parse : parses) {
			t.start();
			ParsingSentence<?, ?> ps = p.getParsingSentenceFor(parse, infFact);
			ps.getTrainingExample();	// forces features to be computed
			long time = t.stop();
			System.out.println(parse.getSentence().size() + "\t" + time);
		}
		double time = t.totalTimeInSeconds();
		System.out.printf("total time %.1f seconds, %s", time, t.toString());
	}
}
