package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.util.Configuration;
import edu.jhu.hlt.fnparse.util.DefaultConfiguration;

public class Trainer {

	public static void main(String[] args) {
		
		FrameNetParser parser = new Semaforic();
		Configuration conf = new DefaultConfiguration();
		List<FrameInstance> all = conf.getFrameInstanceProvider().getFrameInstances();
		Collections.shuffle(all);
		int numTrain = (int) (all.size() * 0.7d);
		List<FrameInstance> train = new ArrayList<FrameInstance>();
		List<FrameInstance> test = new ArrayList<FrameInstance>();
		for(int i=0; i<all.size(); i++)
			(i < numTrain ? train : test).add(all.get(i));
		
		
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
