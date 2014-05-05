package edu.jhu.hlt.fnparse.experiment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.Parser.Mode;
import edu.jhu.hlt.fnparse.util.MultiTimer;

public class FeatureComputationBenchmark {
	
	public static void main(String[] args) {
		boolean latentDeps = false;
		Parser parser = new Parser(Mode.PIPELINE_FRAME_ARG, latentDeps, false);
		parser.params.usePredictedFramesToTrainRoleId = false;	// we don't have a frame-id model trained here
		
		Map<FNParse, Long> timeTakenPerExample = new HashMap<FNParse, Long>();
		
		MultiTimer timer = new MultiTimer();
		int c = 0, maxIter = 60;
		int k = 1;
		timer.get("read-parse", true).setPrintInterval(k);
		timer.get("read-parse", true).ignoreFirstTime = true;
		timer.get("make-example", true).setPrintInterval(k);
		timer.get("make-example", true).ignoreFirstTime = true;
		List<LabeledFgExample> fges = new ArrayList<>();
		Iterator<FNParse> iter = FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences();
		while(iter.hasNext() && c++ < maxIter) {

			timer.start("read-parse");
			FNParse p = iter.next();
			timer.stop("read-parse");

			timer.start("make-example");
			LabeledFgExample eg = parser.getExampleForTraining(p).get(0);
			long t = timer.stop("make-example");
			
			timeTakenPerExample.put(p, t);

			fges.add(eg);
			if(fges.size() % k == 0)
				ParserExperiment.printMemUsage();
		}
		
		System.out.printf("done, took %.1f seconds to do %d examples\n", timer.totalTimeInSeconds(), c);
		
		sizeVsTime(timeTakenPerExample);
	}
	
	private static void sizeVsTime(Map<FNParse, Long> times) {
		for(Map.Entry<FNParse, Long> x : times.entrySet()) {
			System.out.printf("%d %d\n", x.getKey().getSentence().size(), x.getValue());
		}
	}

}
