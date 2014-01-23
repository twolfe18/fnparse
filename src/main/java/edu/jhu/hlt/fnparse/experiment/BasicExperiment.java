package edu.jhu.hlt.fnparse.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.data.FNFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.inference.FGFNParser;

/**
 * read data from Framenet 1.5
 * train a parser
 * evaluate that parser
 * @author travis
 */
public class BasicExperiment {

	public static void main(String[] args) {
		
		FrameIndex frameIndex = new FrameIndex();
		FrameInstanceProvider instancePrv = new FNFrameInstanceProvider();
		List<Sentence> all = instancePrv.getFrameInstances();
		double propTest = 0.2d;
		boolean saveSplit = true;
		List<Sentence> train = new ArrayList<Sentence>();
		List<Sentence> test = new ArrayList<Sentence>();
		DataSplitter ds = new DataSplitter();
		ds.split(all, train, test, propTest, saveSplit);
		
		FGFNParser parser = new FGFNParser(frameIndex.allFrames());
		parser.train(train);
		List<Sentence> testParsed = parser.parse(test);
		
		Map<String, Double> results = BasicEvaluation.evaluate(test);
		BasicEvaluation.showResults("BasicExperiment", results);
	}
}
