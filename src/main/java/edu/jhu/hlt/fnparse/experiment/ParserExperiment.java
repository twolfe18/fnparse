package edu.jhu.hlt.fnparse.experiment;

import java.util.*;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser;

public class ParserExperiment {

	public static final boolean hurryUp = true;
	
	public static void main(String[] args) {
		
		// get the data
		DataSplitter ds = new DataSplitter();
		List<FNParse> all = FileFrameInstanceProvider.fn15trainFIP.getParsedSentences();
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(all, train, test, 0.2d, "fn15_train");
		train = getSuitableTrainingExamples(train);	// get rid of nasty examples
		
		if(hurryUp) {
			int nTrain = 25;
			int nTest = 25;
			train = DataUtil.reservoirSample(train, nTrain);
			test = DataUtil.reservoirSample(test, nTest);
		}
		
		// train and evaluate along the way
		Parser parser = new Parser();
		for(int epoch=0; epoch<5; epoch++) {
			int passes = 1;
			int batchSize = 2;
			double k = 4d;
			double lrMult = k / (k + epoch);
			parser.train(train, passes, batchSize, lrMult);
			List<FNParse> predicted = parser.parseWithoutPeeking(test);
			Map<String, Double> results = BasicEvaluation.evaluate(test, predicted);
			BasicEvaluation.showResults("after " + (epoch+1) + " epochs", results);
		}
	}
	
	public static List<FNParse> getSuitableTrainingExamples(List<FNParse> train) {
		final int maxArgWidth = 10;
		final int maxTargetWidth = 3;
		List<FNParse> buf = new ArrayList<FNParse>();
		outer: for(FNParse t : train) {
			for(FrameInstance fi : t.getFrameInstances()) {
				if(fi.getTarget().width() > maxTargetWidth)
					continue outer;
				for(int k=0; k<fi.numArguments(); k++)
					if(fi.getArgument(k).width() > maxArgWidth)
						continue outer;
			}
			buf.add(t);
		}
		return buf;
	}
}
