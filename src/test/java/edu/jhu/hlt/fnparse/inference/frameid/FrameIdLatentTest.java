package edu.jhu.hlt.fnparse.inference.frameid;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Before;
import org.junit.Test;

import edu.jhu.gm.train.CrfObjective;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNIterFilters;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.Util;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.TaggingDiff;
import edu.jhu.hlt.fnparse.util.Timer;

/**
 * The purpose of these tests are to take a small number of examples, train a
 * regular model and a latent-syntax one, and check that the marginal likelihood
 * is higher for the latent-syntax one. Another test will check that the
 * predictions are just as good or better. Lastly, I'll include some timing
 * information so that we know how much longer the latent version is taking.
 * 
 * For now I'll start with frameId + latent-deps
 * 
 * @author travis
 */
public class FrameIdLatentTest {
	
	@Before
	public void setupLogging() {
		Logger.getLogger(CrfObjective.class).setLevel(Level.INFO);
	}

	@Test
	public void frameIdWithLatentDeps() {
		final StdEvalFunc eval = BasicEvaluation.targetMicroF1;

		ParserParams regParams = new ParserParams();
		regParams.useSyntaxFeatures = true;
		regParams.useLatentConstituencies = false;
		regParams.useLatentDepenencies = false;
		FrameIdStage regular = new FrameIdStage(regParams);
		regular.params.tuneOnTrainingData = true;

		ParserParams latentParams = new ParserParams();
		latentParams.useSyntaxFeatures = true;
		latentParams.useLatentConstituencies = false;
		latentParams.useLatentDepenencies = true;
		FrameIdStage latent = new FrameIdStage(latentParams);
		latent.params.tuneOnTrainingData = true;

		Timer tReg = new Timer("regular-frameId", 1, false);
		Timer tLat = new Timer("latent-frameId", 1, false);

		for (FNParse p : parseToEvaluateOn()) {
			System.out.println("\n###########################################");
			System.out.println("parsing " + p.getId());

			tReg.start();
			FNTagging yhatRegular = trainAndThenPredictFrames(regular, p);
			tReg.stop();

			tLat.start();
			FNTagging yhatLatent = trainAndThenPredictFrames(latent, p);
			tLat.stop();

			checkNonNullFrameInstances(yhatLatent);
			checkNonNullFrameInstances(yhatRegular);

			FNTagging yt = DataUtil.convertParseToTagging(p);
			checkNonNullFrameInstances(yt);

			System.out.println("gold     = " + Describe.fnTagging(yt));
			System.out.println("regular  = " + Describe.fnTagging(yhatRegular));
			System.out.println("latent   = " + Describe.fnTagging(yhatLatent));
			System.out.println("diff gold regular:\n"
					+ TaggingDiff.diff(yt, yhatRegular, false));
			System.out.println("diff gold latent:\n"
					+ TaggingDiff.diff(yt, yhatLatent, false));

			double regF1 = eval.evaluate(new SentenceEval(yt, yhatRegular));
			double latentF1 = eval.evaluate(new SentenceEval(yt, yhatLatent));

			// I didn't realize this initially, but I am doing an overfitting
			// test, and they should basically both be able to get perfect
			// performance...
			assertTrue(regF1 > 0.99999999d);

			// Check perf(latent) >= perf(regular)
			if (regF1 > latentF1) {
				System.err.println("latentF1=" + latentF1);
				System.err.println("regF1=" + regF1);
				assertTrue("you have a bug", false);
			}
		}
	}
	
	private static void checkNonNullFrameInstances(FNTagging t) {
		for (FrameInstance fi : t.getFrameInstances()) 
			assertNotNull(fi);
	}
	
	private static FNTagging trainAndThenPredictFrames(
			FrameIdStage frameId,
			FNParse p) {
		List<Sentence> x = Arrays.asList(p.getSentence());
		List<FNParse> y = Arrays.asList(p);
		frameId.scanFeatures(x, y, 999, 99_999_999);
		frameId.train(Arrays.asList(p));
		return frameId.setupInference(x, null).decodeAll().get(0);
	}
	
	public static List<FNParse> parseToEvaluateOn() {
		boolean debug = true;
		if (debug) {
			return Arrays.asList(FNIterFilters.findBySentenceId(
				FileFrameInstanceProvider.debugFIP.getParsedSentences(),
				"FNFUTXT1274783"));
		} else {
			return Util.filterOutExamplesThatCantBeFit(DataUtil.iter2list(
				FileFrameInstanceProvider.debugFIP.getParsedSentences()));
		}
	}
}
