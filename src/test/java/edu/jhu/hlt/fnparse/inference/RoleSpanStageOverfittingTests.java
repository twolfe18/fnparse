package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNIterFilters;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadToSpanStage;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.FNDiff;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.hlt.fnparse.util.ModelIO;

public class RoleSpanStageOverfittingTests {
	public static final Logger LOG =
			Logger.getLogger(RoleSpanStageOverfittingTests.class);

	@Test
	public void test() {
		StdEvalFunc eval = BasicEvaluation.fullMicroF1;
		String features = null;
		RoleHeadToSpanStage rss = new RoleHeadToSpanStage(new GlobalParameters(), features);
		Map<String, String> conf = new HashMap<>();
		conf.put("maxArgRoleExpandLeft", "99");
		conf.put("maxArgRoleExpandRight", "99");
		conf.put("regularizer.RoleHeadToSpanStage", "999999999");
		conf.put("passes.RoleHeadToSpanStage", "10");
		conf.put("learningRate.RoleHeadToSpanStage", "1");
		rss.configure(conf);
		List<FNParse> y = parseToEvaluateOn();
		List<FNParse> x = DataUtil.convertArgumenSpansToHeads(
				y, SemaforicHeadFinder.getInstance());
		for (int i = 0; i < y.size(); i++) {
			List<FNParse> yi = y.subList(i, i + 1);
			List<FNParse> xi = x.subList(i, i + 1);

			//params.setFeatureAlphabet(new Alphabet<String>());
			rss.getGlobalParameters().getFeatureNames().startGrowth();
			rss.scanFeatures(xi, yi, 999, 999_999);
			rss.train(xi, yi);

			FNParse yi_hat = rss.setupInference(xi, yi).decodeAll().get(0);

			double f1 = eval.evaluate(new SentenceEval(yi.get(0), yi_hat));
			LOG.info("gold: " + Describe.fnParse(yi.get(0)));
			LOG.info("hyp:  " + Describe.fnParse(yi_hat));
			LOG.info("diff:\n" + FNDiff.diffArgs(yi.get(0), yi_hat, false));
			LOG.info(String.format("%.4f for %d frames in %s\n",
					f1, y.get(0).getFrameInstances().size(), y.get(0).getId()));
			ModelIO.writeHumanReadable(
					rss.getWeights(),
					rss.getGlobalParameters().getFeatureNames(),
					new File("saved-models/testing/argExpansion-weights."
							+ yi_hat.getSentence().getId() + ".txt"),
					true);
			assertTrue(f1 > 0.99999d);
		}
	}

	public static List<FNParse> parseToEvaluateOn() {
		boolean debug = false;
		if (debug) {
			return Arrays.asList(FNIterFilters.findBySentenceId(
					FileFrameInstanceProvider.debugFIP.getParsedSentences(),
					"FNFUTXT1274789"));
		} else {
			return TestingUtil.filterOutExamplesThatCantBeFit(DataUtil.iter2list(
					FileFrameInstanceProvider.debugFIP.getParsedSentences()));
		}
	}
}
