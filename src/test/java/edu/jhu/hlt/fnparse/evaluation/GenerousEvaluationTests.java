package edu.jhu.hlt.fnparse.evaluation;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.util.FrameInstanceCorrupter;

public class GenerousEvaluationTests {
	
	private List<SentenceEval> instances;
	
	@Before
	public void getInstances() {
		instances = new ArrayList<>();
		FrameInstanceCorrupter corr = new FrameInstanceCorrupter();
		List<FNParse> parses = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		for(FNParse gold : parses) {
			// just going to make random predictions rather than calling a parser
			FNParse hyp = corr.corrupt(gold);
			instances.add(new SentenceEval(gold, hyp));
		}
	}
	
	@Test
	public void basic() {
		double p0 = BasicEvaluation.fullMacroPrecision.evaluate(instances);
		double p1 = GenerousEvaluation.generousPrecision.evaluate(instances);
		assertTrue(p0 <= p1);

		double r0 = BasicEvaluation.fullMacroRecall.evaluate(instances);
		double r1 = GenerousEvaluation.generousRecall.evaluate(instances);
		assertTrue(r0 <= r1);

		double f0 = BasicEvaluation.fullMacroF1.evaluate(instances);
		double f1 = GenerousEvaluation.generousF1.evaluate(instances);
		assertTrue(f0 <= f1);
	}

}
