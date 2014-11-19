package edu.jhu.hlt.fnparse.inference.frameid;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import edu.jhu.hlt.fnparse.inference.TestingUtil;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.FNDiff;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.util.Alphabet;

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
	
	public static boolean printFeatures = false;
	
	@Before
	public void setupLogging() {
		Logger.getLogger(CrfObjective.class).setLevel(Level.INFO);
	}

	@Test
	public void frameIdWithLatentDeps() {
		final StdEvalFunc eval = BasicEvaluation.targetMicroF1;

		Map<String, String> conf = new HashMap<>();
		conf.put("tuneOnTrainingData.FrameIdStage", "true");

		String features = null;

		FrameIdStage regular = new FrameIdStage(new GlobalParameters(), features);
		regular.setSyntaxMode("regular");
		regular.configure(conf);

		FrameIdStage latent = new FrameIdStage(new GlobalParameters(), features);
		latent.setSyntaxMode("latent");
		latent.configure(conf);

		Timer tReg = new Timer("regular-frameId", 1, false);
		Timer tLat = new Timer("latent-frameId", 1, false);

		for (FNParse p : parseToEvaluateOn()) {
			System.out.println("\n###########################################");
			System.out.println("parsing " + p.getId());

			System.out.println("### REGULAR ##################################");
			tReg.start();
			FNTagging yhatRegular = trainAndThenPredictFrames(regular, p);
			tReg.stop();

			System.out.println("### LATENT ##################################");
			tLat.start();
			FNTagging yhatLatent = trainAndThenPredictFrames(latent, p);
			tLat.stop();

			checkNonNullFrameInstances(yhatLatent);
			checkNonNullFrameInstances(yhatRegular);

			FNTagging yt = DataUtil.convertParseToTagging(p);
			checkNonNullFrameInstances(yt);

			System.out.println("### EVALUATION ##################################");
			System.out.println("gold     = " + Describe.fnTagging(yt));
			System.out.println("regular  = " + Describe.fnTagging(yhatRegular));
			System.out.println("latent   = " + Describe.fnTagging(yhatLatent));
			System.out.println("diff gold regular:\n"
					+ FNDiff.diffFrames(yt, yhatRegular, false));
			System.out.println("diff gold latent:\n"
					+ FNDiff.diffFrames(yt, yhatLatent, false));

			double regF1 = eval.evaluate(new SentenceEval(yt, yhatRegular));
			double latentF1 = eval.evaluate(new SentenceEval(yt, yhatLatent));

			// I didn't realize this initially, but I am doing an overfitting
			// test, and they should basically both be able to get perfect
			// performance...
			//assertTrue(regF1 > 0.99999999d);

			// Check perf(latent) >= perf(regular)
			System.out.println("latentF1=" + latentF1);
			System.out.println("regF1=" + regF1);
			if (regF1 > latentF1)
				System.out.println("pay attention");
			assertTrue("you have a bug!", latentF1 >= regF1);
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
		final Alphabet<String> alph = frameId.getGlobalParameters().getFeatureNames();
		alph.startGrowth();
		frameId.scanFeatures(x, y, 999, 99_999_999);
		frameId.train(Arrays.asList(p));
		alph.stopGrowth();

		// Try to figure out if we have 0 weights for the f_it ~ l_ij factors
		if (printFeatures) {
			frameId.getWeights().apply(new FnIntDoubleToDouble() {
				@Override
				public double call(int idx, double val) {
					if (idx < alph.size()) {
						String feat = (String) alph.lookupObject(idx);
						System.out.printf("%-25s %.2f\n", feat, val);
					} else {
						System.out.println(idx + " is not in the alphabet");
					}
					return val;
				}
			});
		}
		return frameId.setupInference(x, null).decodeAll().get(0);
	}

	public static List<FNParse> parseToEvaluateOn() {
		boolean debug = false;
		if (debug) {
			return Arrays.asList(FNIterFilters.findBySentenceId(
				FileFrameInstanceProvider.debugFIP.getParsedSentences(),
				"FNFUTXT1274796"));
				//"FNFUTXT1274795"));
				//"FNFUTXT1274783"));
		} else {
			return TestingUtil.filterOutExamplesThatCantBeFit(DataUtil.iter2list(
				FileFrameInstanceProvider.debugFIP.getParsedSentences()));
		}
	}
}
