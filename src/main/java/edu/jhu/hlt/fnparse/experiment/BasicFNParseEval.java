package edu.jhu.hlt.fnparse.experiment;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.data.FNFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.FrameNetParser;
import edu.jhu.hlt.fnparse.inference.FrameNetParserTrainer;
import edu.jhu.hlt.fnparse.inference.SemaforicTrainer;

/**
 * read data from Framenet 1.5
 * train a parser
 * evaluate that parser
 * @author travis
 */
public class BasicFNParseEval {

	public static void main(String[] args) {
		FrameInstanceProvider instancePrv = new FNFrameInstanceProvider();
		List<Sentence> all = instancePrv.getFrameInstances();
		FrameNetParserTrainer trainer = new SemaforicTrainer();
		
		double propTest = 0.2d;
		boolean saveSplit = true;
		List<Sentence> train = new ArrayList<Sentence>();
		List<Sentence> test = new ArrayList<Sentence>();
		DataSplitter ds = new DataSplitter();
		ds.split(all, train, test, propTest, saveSplit);
		
		FrameNetParser parser = trainer.train(train);
		parser.parse(test);
		
		
	}
}
