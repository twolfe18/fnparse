package edu.jhu.hlt.fnparse.experiment;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.inference.Parser;

public class FrameIdMistakes {
	
	public static void main(String[] args) {
		if(args.length != 1) {
			System.out.println("please provide a frameId model file");
			return;
		}
		
		File f = new File(args[0]);
		Parser parser = new Parser(f);
		FrameInstanceProvider fip = FileFrameInstanceProvider.dipanjantrainFIP;
		List<FNParse> gold = DataUtil.iter2list(fip.getParsedSentences());
		List<FNParse> hyp = parser.parseWithoutPeeking(gold);
		List<SentenceEval> instances = BasicEvaluation.zip(gold, hyp);
		for(SentenceEval se : instances) {
			if(BasicEvaluation.targetMicroF1.evaluate(Arrays.asList(se)) > 0.5d)
				continue;
			System.out.println(se.longString());
		}
		
		double f1 = BasicEvaluation.targetMicroF1.evaluate(instances);
		System.out.printf("total target F1 = %.2f on %d instances\n", f1 * 100d, instances.size());
	}

}
