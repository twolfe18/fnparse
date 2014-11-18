package edu.jhu.hlt.fnparse.inference.roleid;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNIterFilters;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.TestingUtil;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadStage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.FNDiff;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.optimize.functions.L2;

public class RoleIdStageOverfittingTests {

	@Test
	public void test() {
	  int maxTest = 10;
		StdEvalFunc eval = BasicEvaluation.argOnlyMicroF1;
		ParserParams params = new ParserParams();
		params.useSyntaxFeatures = true;
		StringBuilder fs = new StringBuilder();
		fs.append("RoleHeadStage * frameRole * head1Word * Word-2-grams-between-<S>-and-Head1 * Word-2-grams-between-Head1-and-</S>");
		fs.append("+ RoleHeadStage * frameRole * head1Word * Word-2-grams-between-Head1-and-</S>");
		fs.append("+ RoleHeadStage * frameRole * head1Word * Word-2-grams-between-<S>-and-Head1");
		fs.append("+ RoleHeadStage * frameRole * head1Word");
		fs.append("+ RoleHeadStage * frameRole * head1Word * head1Pos");
		params.setFeatureTemplateDescription(fs.toString());
		RoleHeadStage rid = new RoleHeadStage(params, params);
		rid.params.learningRate = 0.01d;
		rid.params.passes = 200;
		rid.params.tuneOnTrainingData = true;
		rid.params.regularizer = new L2(999_999_999d);
		rid.disablePruning();
		int tested = 0;
		for (FNParse p : parseToEvaluateOn()) {

			// Convert all arguments to just width-1 spans
			p = DataUtil.convertArgumenSpansToHeads(
					Arrays.asList(p), params.headFinder).get(0);

			List<FNTagging> x = Arrays.asList((FNTagging) p);
			List<FNParse> y = Arrays.asList(p);
			System.out.printf("### Working on %s #############################",
					p.getId());
			System.out.println("gold: " + Describe.fnParse(p));

			params.getAlphabet().startGrowth();
			rid.scanFeatures(x, y, 999, 999_999);
			rid.train(x, y);

			StageDatumExampleList<FNTagging, FNParse> data =
					rid.setupInference(x, y);

			FNParse yhat = data.decodeAll().get(0);
			double f1 = eval.evaluate(new SentenceEval(p, yhat));

			ModelIO.writeHumanReadable(
					rid.getWeights(),
					params.getAlphabet(),
					new File("saved-models/testing/roleId-overfitting-" + p.getId() + ".txt"),
					true);

			System.out.println("gold: " + Describe.fnParse(p));
			System.out.println("hyp:  " + Describe.fnParse(yhat));
			System.out.println("diff:\n" + FNDiff.diffArgs(p, yhat, false));
			System.out.printf("%.4f for %d frames in %s\n",
					f1, p.getFrameInstances().size(), p.getId());
			if (f1 < 0.9999d)
				System.out.println("about to fail");
			assertTrue("couldn't fit " + p.getId(), f1 >= 0.9999d);

			if (++tested >= maxTest)
			  break;
		}
	}

	public static List<FNParse> parseToEvaluateOn() {
		boolean debug = false;
		if (debug) {
			return Arrays.asList(FNIterFilters.findBySentenceId(
					//FileFrameInstanceProvider.debugFIP.getParsedSentences(),
					FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences(),
					"FNFUTXT1274943"));
					//"FNFUTXT1274783"));
		} else {
			return TestingUtil.filterOutExamplesThatCantBeFit(DataUtil.iter2list(
					FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences()));
					//FileFrameInstanceProvider.debugFIP.getParsedSentences()));
		}
	}
}
