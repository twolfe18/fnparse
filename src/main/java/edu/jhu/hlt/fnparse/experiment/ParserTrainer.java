package edu.jhu.hlt.fnparse.experiment;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNIterFilters;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.GenerousEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.roleid.RoleIdStage;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;
import edu.jhu.hlt.fnparse.inference.stages.RoleSpanStage;
import edu.jhu.hlt.fnparse.util.ArrayJobHelper;
import edu.jhu.hlt.fnparse.util.ArrayJobHelper.Option;
import edu.jhu.hlt.fnparse.util.DataSplitReader;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.FNDiff;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.optimize.functions.L2;

/**
 * This class trains PipelinedFnParsers where there are two types of options:
 * 1) run-specific options like whether to do frameId or argId which are
 *    specified as command line options.
 * 2) grid-job-specific options like whether the regularizer should be 1e-3 or
 *    1e-4, which serve as the parameters to be swept.
 * 
 * Concerning Training:
 * - we always use the gold output from stage (N-1) to train stage N.
 * 
 * See ParserExperimentWrapper.qsub for how this class interacts with the
 * grid engine.
 * 
 * @author travis
 */
public class ParserTrainer {

	public static final Logger LOG = Logger.getLogger(ParserTrainer.class);

	// These specify the names of the files that are expected to be in the
	// previous stage's model directory.
	// NOTE: This is the old way of doing things, TODO remove this
	public static final String ALPHABET_NAME = "features.alphabet.txt";
	public static final String MODEL_NAME = "model.gz";
	public static final String SER_MODEL_NAME = "model.ser.gz";

	public static final File SENTENCE_ID_SPLITS =
				new File("toydata/development-split.dipanjan-train.txt");

	public static void main(String[] args) throws IOException {
	  double perf = new ParserTrainer().run(args);
	  System.out.println(perf);
	}

	public double run(String[] args) throws IOException {

		if(args.length != 5) {
			System.out.println("Please provide:");
			System.out.println("1) A mode. Possible values:");
			System.out.println("     \"frameId\" trains a model to identity frame targets in a sentence");
			System.out.println("     \"argId\" trains a model to find the heads of the arguments of frames realized in a sentence");
			System.out.println("     \"argSpans\" trains a model to determine the constituency boundaries of arguments");
			System.out.println("2) A job index for sweep parameters (run with -1 to see options)");
			System.out.println("3) A working directory (for output files)");
			System.out.println("4) A feature set string description (template language)");
			System.out.println("5) A syntax mode. Possible values:");
			System.out.println("     \"none\" means that features have no access to syntax information");
			System.out.println("     \"regular\" means that 1-best parses are used for features");
			System.out.println("     \"latent\" means that latent syntax is jointly reasoned about with prediction");
			return -1d;
		}
		LOG.debug("[main] args=" + Arrays.toString(args));

		final String mode = args[0];
		final int jobIdx = Integer.parseInt(args[1]);
		final File workingDir = new File(args[2]);
		final String featureString = args[3];
		final String syntaxMode = args[4];
		if(!workingDir.isDirectory()) workingDir.mkdirs();

		// Validation
		if (!Arrays.asList("frameId", "argId", "argSpans").contains(mode))
			throw new IllegalArgumentException("illegal mode: " + mode);
		if(!Arrays.asList("regular", "none", "latent").contains(syntaxMode))
			throw new IllegalStateException("unknown syntax mode: " + syntaxMode);

		long start = System.currentTimeMillis();
		LOG.info("[main] workingDir = " + workingDir.getPath());

		// Set up array job configurations
		ArrayJobHelper ajh = new ArrayJobHelper();
		Option<Integer> nTrainLimit = ajh.addOption("nTrainLimit",
				Arrays.asList(50, 400, 999999));
		Option<Integer> passes = ajh.addOption("passes",
				Arrays.asList(2, 5));
		Option<Integer> batchSize = ajh.addOption("batchSize",
				Arrays.asList(5, 50));
		Option<Double> regularizer = ajh.addOption("regularizer",
				Arrays.asList(10000d, 100000d, 1000000d));

		// Choose an array job configuration
		if(jobIdx < 0) {
			System.out.println(ajh.helpString(999));
			return -2d;
		} else {
			ajh.setConfig(jobIdx);
		}
		LOG.info("config = " + ajh.getStoredConfig());

		// Get the data
		List<FNParse> all = DataUtil.iter2list(
				new FNIterFilters.SkipSentences<FNParse>(
				FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences(),
				Arrays.asList("FNFUTXT1274640", "FNFUTXT1279095")));
		DataSplitReader dsr = new DataSplitReader(SENTENCE_ID_SPLITS);
		List<FNParse> train = dsr.getSection(all, "train", false);
		List<FNParse> tune = dsr.getSection(all, "tune", false);
		List<FNParse> test = dsr.getSection(all, "test", false);
		if(nTrainLimit.get() < train.size()) {
			train = DataUtil.reservoirSample(
					train, nTrainLimit.get(), new Random(9001));
		}
		LOG.info("#train=" + train.size()
				+ " #tune=" + tune.size()
				+ " #test=" + test.size());
		printMemUsage();

		// Create parser
		ParserParams parserParams = new ParserParams();
		parserParams.setFeatureTemplateDescription(featureString);
		// Syntax mode (e.g. latent vs regular vs none)
		if ("latent".equals(syntaxMode)) {
			parserParams.useLatentDepenencies = true;
			parserParams.useSyntaxFeatures = false;
		} else if ("regular".equals(syntaxMode)) {
			parserParams.useLatentDepenencies = false;
			parserParams.useSyntaxFeatures = true;
		} else if ("none".equals(syntaxMode)) {
			parserParams.useLatentDepenencies = false;
			parserParams.useSyntaxFeatures = false;
		} else {
			throw new RuntimeException("illegal mode: " + syntaxMode);
		}
		PipelinedFnParser parser = new PipelinedFnParser(parserParams);

		// Set the mode (e.g. frameId vs argId)
		if ("frameId".equals(mode)) {
			FrameIdStage fIdStage = (FrameIdStage) parser.getFrameIdStage();
			fIdStage.params.batchSize = batchSize.get();
			fIdStage.params.passes = passes.get();
			fIdStage.params.regularizer = new L2(regularizer.get());
			parser.disableArgId();
		} else if ("argId".equals(mode)) {
			RoleIdStage aid = (RoleIdStage) parser.getArgIdStage();
			aid.params.batchSize = batchSize.get();
			aid.params.passes = passes.get();
			aid.params.regularizer = new L2(regularizer.get());
			parser.useGoldFrameId();
			//parser.useGoldArgSpans();	// takes gold spans regardless of head
			parser.disableArgSpans();
		} else {
			assert "argSpans".equals(mode);
			RoleSpanStage rss = (RoleSpanStage) parser.getArgSpanStage();
			rss.params.batchSize = batchSize.get();
			rss.params.passes = passes.get();
			rss.params.regularizer = new L2(regularizer.get());
			parser.useGoldArgId();
		}

		// Compute the feature alphabet
		int maxTimeInMinutes = 45;
		int maxFeaturesAdded = 10_000_000;
		parser.scanFeatures(train, maxTimeInMinutes, maxFeaturesAdded);

		// Train
		parser.train(train);
		LOG.info("After training, #features=" + parser.getAlphabet().size());
		printMemUsage();

		// Serialize the model using Java serialization
		parser.saveModel(workingDir);
		if ("frameId".equals(mode)) {
		  ModelIO.writeHumanReadable(
		      parser.getFrameIdStage().getWeights(),
		      parser.getAlphabet(),
		      new File(workingDir, "model.txt"),
		      true);
		}

		// Evaluate (test data)
		List<FNParse> predicted;
		Map<String, Double> results;
		int maxTestEval = 100;
		List<FNParse> testSubset = test.size() > maxTestEval
				? DataUtil.reservoirSample(test, maxTestEval, parser.getParams().rand)
				: test;
		LOG.info("Predicting on " + testSubset.size() + " test examples");
		predicted = parser.parse(
				DataUtil.stripAnnotations(testSubset), testSubset);
		results = BasicEvaluation.evaluate(testSubset, predicted);
		BasicEvaluation.showResults(
				"[test] after " + passes.get() + " passes", results);
		printMemUsage();
		if ("argId".equals(mode))
			printMistakenArgHeads(testSubset, predicted);
		double ret = "frameId".equals(mode)
		    ? results.get("TargetMicroF1")
		    : results.get("FullMicroF1");

		// Evaluate (train data)
		int maxTrainEval = 150;
		List<FNParse> trainSubset = train.size() > maxTrainEval
				? DataUtil.reservoirSample(train, maxTrainEval, parser.getParams().rand)
				: train;
		LOG.info("Predicting on train (sub)set of size " + trainSubset.size());
		predicted = parser.parse(
				DataUtil.stripAnnotations(trainSubset), trainSubset);
		results = BasicEvaluation.evaluate(trainSubset, predicted);
		BasicEvaluation.showResults(
				"[train] after " + passes.get() + " passes", results);
		printMemUsage();

		LOG.info("done, took "
				+ (System.currentTimeMillis() - start) / (1000d * 60)
				+ " minutes");
		return ret;
	}

	public static void printMistakenArgHeads(
			List<FNParse> gold,
			List<FNParse> hyp) {
		assert gold != null && hyp != null && gold.size() == hyp.size();
		for (int i = 0; i < gold.size(); i++)
			printMistakenArgHeads(gold.get(i), hyp.get(i));
	}

	public static void printMistakenArgHeads(FNParse gold, FNParse hyp) {
		List<SentenceEval> se = Arrays.asList(new SentenceEval(gold, hyp));
		double f1 = GenerousEvaluation.generousF1.evaluate(se);
		if (f1 < 0.8d) {
			if ("FNFUTXT1228804".equals(gold.getId()))
				LOG.info("debug this");
			double p = GenerousEvaluation.generousPrecision.evaluate(se);
			double r = GenerousEvaluation.generousRecall.evaluate(se);
			Sentence s = gold.getSentence();
			LOG.info(s.getId() + " has F1=" + f1
					+ " precision=" + p + " recall=" + r);
			for (int i = 0; i < s.size(); i++) {
				String parent = "ROOT";
				if (s.governor(i) < s.size() && s.governor(i) >= 0)
					parent = s.getWord(s.governor(i));
				LOG.info(String.format("% 3d %-15s %-15s %-15s %-15s",
					i, s.getWord(i), s.getPos(i), s.dependencyType(i), parent));
			}
			LOG.info("gold:\n" + Describe.fnParse(gold));
			LOG.info("hyp:\n" + Describe.fnParse(hyp));
			LOG.info("errors:\n" + FNDiff.diffArgs(gold, hyp, false));
		}
	}

	public static void printMemUsage() {
		double used = Runtime.getRuntime().totalMemory();
		used /= 1024d * 1024d * 1024d;
		double free = Runtime.getRuntime().maxMemory();
		free /= 1024d * 1024d * 1024d;
		free -= used;
		LOG.info(String.format("using %.2f GB, %.2f GB free", used, free));
	}
}

