package edu.jhu.hlt.fnparse.serialization;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.BasicRoleFeatures;
import edu.jhu.hlt.fnparse.features.BasicRoleSpanFeatures;
import edu.jhu.hlt.fnparse.features.MinimalRoleFeatures;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.pruning.NoArgPruner;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadToSpanStage;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadStage;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.L2;

public class DepPipelineParserSerTest {
	public static final Logger LOG =
			Logger.getLogger(DepPipelineParserSerTest.class);

	private static boolean enableArgId = true;
	private static boolean enableArgSpans = true;

	@Test
	public void test() throws Exception {
		// Read some data
		List<FNParse> data =
				DataUtil.iter2stream(
				FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences())
				.filter(fi -> fi.numFrameInstances() > 0)
				.limit(30)
				.collect(Collectors.toList());
		for (FNParse p : data)
			LOG.debug(Describe.fnParse(p));

		StdEvalFunc eval = BasicEvaluation.fullMicroF1;
		BasicFrameFeatures.OVERFITTING_DEBUG = true;
		BasicRoleFeatures.OVERFITTING_DEBUG = true;	// not being used
		MinimalRoleFeatures.OVERFITTING_DEBUG = true;
		BasicRoleSpanFeatures.OVERFITTING_DEBUG = true;
		AbstractStage.DEBUG_SER = false;

		// Create some models
		PipelinedFnParser fid = new PipelinedFnParser(new ParserParams());
		fid.disableArgId();
		PipelinedFnParser aid = new PipelinedFnParser(new ParserParams());
		if (enableArgId && enableArgSpans) {
			aid.useGoldFrameId();
		} else if (!enableArgId && enableArgSpans) {
			aid.useGoldArgId();
		} else if (enableArgId && !enableArgSpans) {
			aid.disableArgSpans();	// just predict heads as spans
		} else {
			throw new RuntimeException("dont use this");
		}

		// Turn off all regularization
		Regularizer noReg = new L2(999_999_999d);
		((FrameIdStage) fid.getFrameIdStage()).params.passes = 10;
		((FrameIdStage) fid.getFrameIdStage()).params.learningRate = 1d;
		((FrameIdStage) fid.getFrameIdStage()).params.regularizer = noReg;
		if (enableArgId) {
			((RoleHeadStage) aid.getArgIdStage()).params.passes = 10;
			((RoleHeadStage) aid.getArgIdStage()).params.learningRate = 1d;
			((RoleHeadStage) aid.getArgIdStage()).params.regularizer = noReg;
			((RoleHeadStage) aid.getArgIdStage()).params.argPruner = new NoArgPruner();
			((RoleHeadStage) aid.getArgIdStage()).params.tuneOnTrainingData = true;
		}
		if (enableArgSpans) {
			((RoleHeadToSpanStage) aid.getArgSpanStage()).params.passes = 10;
			((RoleHeadToSpanStage) aid.getArgSpanStage()).params.learningRate = 1d;
			((RoleHeadToSpanStage) aid.getArgSpanStage()).params.regularizer = noReg;
		}

		// Train the models (separately)
		LOG.info("training frameId");
		fid.scanFeatures(data, 1, 10_000_000);
		fid.learnWeights(data);
		LOG.info("training argId");
		aid.scanFeatures(data, 1, 10_000_000);
		aid.learnWeights(data);

		// Save the models
		LOG.info("saving model...");
		File s1 = new File("saved-models/testing/s1");
		File s2 = new File("saved-models/testing/s2");
		File s3 = new File("saved-models/testing/s3");
		s1.delete(); s2.delete(); s3.delete();
		fid.getFrameIdStage().saveModel(s1);
		aid.getArgIdStage().saveModel(s2);
		aid.getArgSpanStage().saveModel(s3);
		// NOTE: I don't need to save the alphabet because each stage will write
		// out feature names as strings. When they are loaded, the alphabet will
		// be populated.
		LOG.info("fid alph size = " + fid.getAlphabet().size());
		LOG.info("aid alph size = " + aid.getAlphabet().size());

		/*
		ModelIO.writeHumanReadable(
				fid.getFrameIdStage().getWeights(),
				fid.getAlphabet(),
				new File("saved-models/testing/fid1.weights"),
				false);
		 */
		ModelIO.writeHumanReadable(
				aid.getArgIdStage().getWeights(),
				aid.getAlphabet(),
				new File("saved-models/testing/aid1.weights"),
				false);
		ModelIO.writeHumanReadable(
				aid.getArgSpanStage().getWeights(),
				aid.getAlphabet(),
				new File("saved-models/testing/ais1.weights"),
				false);
		BasicEvaluation.showResults(
				"after argId training",
				BasicEvaluation.evaluate(data, aid.parse(DataUtil.stripAnnotations(data), data)));

		// Read the models back
		LOG.info("reading model...");
		PipelinedFnParser p = new PipelinedFnParser(new ParserParams());
		p.useGoldFrameId();
		if (enableArgId && enableArgSpans) {
			p.getArgIdStage().loadModel(s2);
			p.getArgSpanStage().loadModel(s3);
		} else if (!enableArgId && enableArgSpans) {
			p.useGoldArgId();
			p.getArgSpanStage().loadModel(s3);
		} else if (enableArgId && !enableArgSpans) {
			p.getArgIdStage().loadModel(s2);
			p.disableArgSpans();
		} else {
			throw new RuntimeException("dont use this");
		}
		LOG.info("alph size = " + p.getAlphabet().size());

		/*
		ModelIO.writeHumanReadable(
				p.getFrameIdStage().getWeights(),
				p.getAlphabet(),
				new File("saved-models/testing/fid2.weights"),
				false);
		 */
		ModelIO.writeHumanReadable(
				p.getArgIdStage().getWeights(),
				p.getAlphabet(),
				new File("saved-models/testing/aid2.weights"),
				false);
		ModelIO.writeHumanReadable(
				p.getArgSpanStage().getWeights(),
				p.getAlphabet(),
				new File("saved-models/testing/ais2.weights"),
				false);

		// Test out the model
		LOG.info("testing model...");
		List<FNParse> data2 = p.parse(DataUtil.stripAnnotations(data), data);
		BasicEvaluation.showResults(
				"after de-serialization",
				BasicEvaluation.evaluate(data, data2));
		double f1 = eval.evaluate(SentenceEval.zip(data, data2));
		LOG.info("f1 = " + f1);
		assertTrue(f1 > 0.9d);
	}
}
