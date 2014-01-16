package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.SemEval07;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.util.FrameInstance;
import edu.jhu.hlt.fnparse.util.Sentence;

public class Trainer {

	public static void main(String[] args) {
		
		FrameNetParser parser = new Semaforic();
		List<FrameInstance> train = SemEval07.getTrain();
		List<FrameInstance> test = SemEval07.getTest();
		
		// train
		parser.train(train);
		
		// predict and evaluate
		List<SentenceEval> instances = new ArrayList<SentenceEval>();
		Map<Sentence, List<FrameInstance>> testG = DataUtil.groupBySentence(test);
		for(Sentence s : testG.keySet()) {
			List<FrameInstance> g = testG.get(s);
			List<FrameInstance> h = parser.parse(s);
			instances.add(new SentenceEval(s, g, h));
		}
		Map<String, Double> results = BasicEvaluation.evaluate(instances);
		BasicEvaluation.showResults("Semaforic", results);
	}
}
