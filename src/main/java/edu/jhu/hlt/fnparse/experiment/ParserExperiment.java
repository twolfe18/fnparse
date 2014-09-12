package edu.jhu.hlt.fnparse.experiment;

import java.util.*;
import java.io.*;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;
import edu.jhu.hlt.fnparse.util.*;
import edu.jhu.hlt.fnparse.util.ArrayJobHelper.Option;
import edu.jhu.hlt.optimize.functions.L2;

/**
 * This class runs experiments where there are two types of options:
 * 1) run-specific options like whether to do frameId or argId which are specified as command line
 *    options
 * 2) grid-job-specific options like whether the regularizer should be 1e-3 or 1e-4, which serve as
 *    the parameters to be swept.
 * 
 * See ParserExperimentWrapper.qsub for how this class interacts with the grid engine.
 * 
 * @author travis
 */
public class ParserExperiment {
	
	public static final Logger LOG = Logger.getLogger(ParserExperiment.class);
	
	// In real experiments, this should be false. This gives the parser access
	// to both 1-best dep syntax + latent syntax.
	public static boolean useSyntaxFeaturesForLatentParser = false;

	public static void main(String[] args) throws IOException {

		if(args.length != 5) {
			System.out.println("Please provide:");
			System.out.println("1) A mode. Possible values:");
			System.out.println("     \"frameId\" will train and test just the frame identification stage");
			System.out.println("     \"argId\" will use gold frames and train and test the argId stage(s)");
			System.out.println("2) A job index for sweep parameters (run with -1 to see options)");
			System.out.println("3) A working directory (for output files)");
			System.out.println("4) An alphabet of pre-computed feature names (use AlphabetComputer or an earlier stage in the pipeline)");
			System.out.println("   This may be the string \"none\" if you want one to be computed (slow)");
			System.out.println("5) A syntax mode. Possible values:");
			System.out.println("     \"none\" means that features have no access to syntax information");
			System.out.println("     \"regular\" means that 1-best parses are used for features");
			System.out.println("     \"latent\" means that latent syntax is jointly reasoned about with prediction");
			return;
		}
		LOG.debug("[main] args=" + Arrays.toString(args));

		final String mode = args[0];
		final int jobIdx = Integer.parseInt(args[1]);
		final File workingDir = new File(args[2]);
		final String featureAlphabet = args[3];
		final String syntaxMode = args[4];
		if(!workingDir.isDirectory()) workingDir.mkdirs();

		// Validation
		if (!Arrays.asList("frameId", "argId").contains(mode))
			throw new IllegalArgumentException("illegal mode: " + mode);
		if(!Arrays.asList("regular", "none", "latent").contains(syntaxMode))
			throw new IllegalStateException("unknown syntax mode: " + syntaxMode);
		if(!"none".equals(featureAlphabet) && !new File(featureAlphabet).isFile()) {
			throw new RuntimeException(featureAlphabet + " is not valid\n"
					+ "Use AlphabetComputer to make an alphabet model");
		}

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
				Arrays.asList(100d, 1000d, 10000d));

		// Choose an array job configuration
		if(jobIdx < 0) {
			System.out.println(ajh.helpString(999));
			return;
		} else {
			ajh.setConfig(jobIdx);
		}
		LOG.info("config = " + ajh.getStoredConfig());

		// Get the data
		DataSplitter ds = new DataSplitter();
		List<FNParse> all = DataUtil.iter2list(
				new FNIterFilters.SkipSentences<FNParse>(
				FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences(),
				Arrays.asList("FNFUTXT1274640", "FNFUTXT1279095")));
		List<FNParse> trainTune = new ArrayList<FNParse>();
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> tune = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(all, trainTune, test, 0.2d, "fn15_train-test");
		int nTest = Math.min(75, (int) (0.15d * trainTune.size()));
		ds.split(trainTune, train, tune, nTest, "fn15_train-tune");

		if(nTrainLimit.get() < train.size()) {
			train = DataUtil.reservoirSample(
					train, nTrainLimit.get(), new Random(9001));
		}


		// TODO I need to write out the train/dev/test split to a text file
		// the evaluation experiment code will read this in and know what to test on


		printMemUsage();
		LOG.info("#train=" + train.size()
				+ " #tune=" + tune.size()
				+ " #test=" + test.size());

		// Create parser
		ParserParams parserParams = new ParserParams();
		// Syntax mode (e.g. latent vs regular vs none)
		if ("latent".equals(syntaxMode)) {
			parserParams.useLatentDepenencies = true;
			parserParams.useSyntaxFeatures =
					useSyntaxFeaturesForLatentParser;
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
		} else {
			parser.getArgIdParams().batchSize = batchSize.get();
			parser.getArgIdParams().passes = passes.get();
			parser.getArgIdParams().regularizer = new L2(regularizer.get());
			parser.useGoldFrameId();
		}

		// Either load a feature alphabet or compute one
		if ("none".equals(featureAlphabet)) {
			int maxMinutes = 45;
			int maxFeatures = 15_000_000;
			// TODO this needs to be stage-aware
			parser.computeAlphabet(train, maxMinutes, maxFeatures);
		} else {
			parser.setAlphabet(ModelIO.readAlphabet(new File(featureAlphabet)));
		}
		LOG.info(String.format(
				"[ParserExperiment] this model was read in from %s, and we're"
				+ "assuming that this model's alphabet (size=%d) already "
				+ "includes all of the features needed to train in %s mode\n",
				featureAlphabet, 
				parser.getParams().getFeatureAlphabet().size(),
				mode));

		// Train
		parser.train(train);
		System.out.printf("[ParserExperiment] after training, #features=%d\n",
				parser.getAlphabet().size());
		printMemUsage();

		// Write parameters out as soon as possible
		parser.writeModel(new File(workingDir, mode + ".model.gz"));

		// Evaluate (test data)
		List<FNParse> predicted;
		Map<String, Double> results;
		int maxTestEval = 100;
		List<FNParse> testSubset = test.size() > maxTestEval
				? DataUtil.reservoirSample(test, maxTestEval, parser.getParams().rand)
				: test;
		System.out.printf(
				"[ParserExperiment] predicting on %d test examples...\n",
				testSubset.size());
		predicted = parser.predict(
				DataUtil.stripAnnotations(testSubset), testSubset);
		results = BasicEvaluation.evaluate(testSubset, predicted);
		BasicEvaluation.showResults(
				"[test] after " + passes.get() + " passes", results);
		printMemUsage();

		// Evaluate (train data)
		int maxTrainEval = 150;
		List<FNParse> trainSubset = train.size() > maxTrainEval
				? DataUtil.reservoirSample(train, maxTrainEval, parser.getParams().rand)
				: train;
		System.out.println("[ParserExperiment] predicting on train (sub)set "
				+ "of size " + trainSubset.size() + "...");
		predicted = parser.predict(
				DataUtil.stripAnnotations(trainSubset), trainSubset);
		results = BasicEvaluation.evaluate(trainSubset, predicted);
		BasicEvaluation.showResults(
				"[train] after " + passes.get() + " passes", results);
		printMemUsage();

		System.out.printf("[ParserExperiment] done, took %.1f minutes\n",
				(System.currentTimeMillis() - start) / (1000d * 60));

		ModelIO.writeHumanReadable(
				parser.getFrameIdWeights(),
				parser.getAlphabet(),
				new File("saved-models/ParserExperiment.model.txt"),
				true);
	}

	
	public static void printMemUsage() {
		double used = Runtime.getRuntime().totalMemory();
		used /= 1024d * 1024d * 1024d;
		double free = Runtime.getRuntime().maxMemory();
		free /= 1024d * 1024d * 1024d;
		free -= used;
		System.out.printf(
				"[ParserExperiment] using %.2f GB, %.2f GB free\n", used, free);
	}
	
}

