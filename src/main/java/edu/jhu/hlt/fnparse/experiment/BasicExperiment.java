package edu.jhu.hlt.fnparse.experiment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
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

	public static final boolean hurryUp = true;
	
	public static void main(String[] args) {
		
		System.out.println("starting basic experiment...");
		FrameInstanceProvider instancePrv = new FNFrameInstanceProvider();
		List<FNParse> all = instancePrv.getFrameInstances();
		System.out.println("#all   = " + all.size());
		
		double propTest = 0.2d;
		boolean saveSplit = true;
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		DataSplitter ds = new DataSplitter();
		String datasetDesc = all.get(0).getSentence().getDataset();
		ds.split(all, train, test, propTest, saveSplit, datasetDesc);
		
		if(hurryUp) {
			train = DataUtil.reservoirSample(train, 6);
			test = DataUtil.reservoirSample(test, 3);
		}
		System.out.println("#train = " + train.size());
		System.out.println("#test  = " + test.size());
		
		System.out.println("data has been read in and split, calling train...");
		boolean joint = false;
		FGFNParser parser = new FGFNParser();
		parser.train(train, joint);
		
		System.out.println("done training, about to parse test sentences...");
		List<FNParse> testParsed = parser.parse(FGFNParser.stipLabels(test), joint);
		Map<String, Double> results = BasicEvaluation.evaluate(test, testParsed);
		BasicEvaluation.showResults("BasicExperiment", results);
	}
}
