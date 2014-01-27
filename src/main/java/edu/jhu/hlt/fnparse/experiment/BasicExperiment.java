package edu.jhu.hlt.fnparse.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.data.FNFrameInstanceProvider;
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

	public static final boolean hurryUp = false;
	
	public static void main(String[] args) {
		
		System.out.println("starting basic experiment...");
		FrameInstanceProvider instancePrv = new FNFrameInstanceProvider();
		List<Sentence> all = instancePrv.getFrameInstances();
		System.out.println("#all   = " + all.size());
		
		double propTest = 0.2d;
		boolean saveSplit = true;
		List<Sentence> train = new ArrayList<Sentence>();
		List<Sentence> test = new ArrayList<Sentence>();
		DataSplitter ds = new DataSplitter();
		ds.split(all, train, test, propTest, saveSplit);
		
		System.out.println("#train = " + train.size());
		System.out.println("#test  = " + test.size());
		if(hurryUp) {
			train = train.subList(0, 10);
			test = test.subList(0, 10);
		}
		
		System.out.println("data has been read in and split, calling train...");
		FGFNParser parser = new FGFNParser();
		parser.train(train);
		
		System.out.println("done training, about to parse test sentences...");
		List<Sentence> testParsed = parser.parse(test);
		Map<String, Double> results = BasicEvaluation.evaluate(test, testParsed);
		BasicEvaluation.showResults("BasicExperiment", results);
	}
}
