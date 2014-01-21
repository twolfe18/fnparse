package edu.jhu.hlt.fnparse.experiment;

import java.util.List;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
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
		List<FrameInstance> instances = instancePrv.getFrameInstances();
		FrameNetParserTrainer trainer = new SemaforicTrainer();
		FrameNetParser parser = trainer.train(DataUtil.groupBySentence(instances));
		// need train test split
		// need to make evaluation instances
		
		// should be an evaluation util that takes a framenet parser and some examples
		
	}
}
