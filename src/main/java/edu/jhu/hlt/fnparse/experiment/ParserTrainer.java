package edu.jhu.hlt.fnparse.experiment;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Logger;

import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNIterFilters;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;
import edu.jhu.hlt.fnparse.util.ArrayJobHelper;
import edu.jhu.hlt.fnparse.util.ArrayJobHelper.Option;
import edu.jhu.hlt.fnparse.util.DataSplitReader;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.fnparse.util.ModelMerger;
import edu.jhu.hlt.optimize.functions.L2;
import edu.jhu.util.Alphabet;

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
	public static final String ALPHABET_NAME = "features.alphabet.txt";
	public static final String MODEL_NAME = "model.gz";

	public static final File SENTENCE_ID_SPLITS =
				new File("toydata/development-split.dipanjan-train.txt");

	public static void main(String[] args) throws IOException {

		if(args.length != 5) {
			System.out.println("Please provide:");
			System.out.println("1) A mode. Possible values:");
			System.out.println("     \"frameId\" trains a model to identity frame targets in a sentence");
			System.out.println("     \"argId\" trains a model to find the heads of the arguments of frames realized in a sentence");
			System.out.println("     \"argSpans\" trains a model to determine the constituency boundaries of arguments");
			System.out.println("2) A job index for sweep parameters (run with -1 to see options)");
			System.out.println("3) A working directory (for output files)");
			//System.out.println("4) A model directory which contains the alphabet and model weights.");
			//System.out.println("     This should be the working directory from the training run of the previous stage");
			//System.out.println("     or \"none\" for frameId");
			System.out.println("4) NOTHING -- this is a placeholder for missing functionality");
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
		//final String prevModelDirName = args[3];
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
			parser.getArgIdParams().batchSize = batchSize.get();
			parser.getArgIdParams().passes = passes.get();
			parser.getArgIdParams().regularizer = new L2(regularizer.get());
			parser.useGoldFrameId();
			parser.disableArgSpans();
		} else {
			assert "argSpans".equals(mode);
			parser.getArgExpansionParams().batchSize = batchSize.get();
			parser.getArgExpansionParams().passes = passes.get();
			parser.getArgExpansionParams().regularizer =
					new L2(regularizer.get());
			parser.useGoldArgId();
		}

		/* THIS IS NOT NEEDED, we're using gold output from previous stage
		// Read in previous model and alphabet
		if (!"none".equals(prevModelDirName)) {
			File prevModel = new File(prevModelDirName);
			if (!prevModel.isDirectory()) {
				throw new RuntimeException(
						prevModelDirName + " is not a directory");
			}
			// Alphabet
			File alphFile = new File(prevModel, ALPHABET_NAME);
			if (!alphFile.isFile()) throw new RuntimeException();
			LOG.info("reading alphabet from " + alphFile.getPath());
			parser.setAlphabet(ModelIO.readAlphabet(alphFile));
			// We don't need to read in the previous model because we are
			// training on oracle output from the previous stage.
			// We shouldn't even copy it over because we're going to let the
			// evaluation experiment choose which model to take from each stage
			// and stitch them together into a full pipeline model.
		} */

		// Compute the feature alphabet
		int maxTimeInMinutes = 45;
		int maxFeaturesAdded = 10_000_000;
		parser.scanFeatures(train, maxTimeInMinutes, maxFeaturesAdded);

		// Train
		parser.train(train);
		System.out.printf("[ParserExperiment] after training, #features=%d\n",
				parser.getAlphabet().size());
		printMemUsage();

		// Write parameters out as soon as possible
		//parser.writeModel(new File(workingDir, MODEL_NAME));
		//parser.writeAlphabet(new File(workingDir, ALPHABET_NAME));
		writeMergedModel(
				new File(workingDir, ALPHABET_NAME),
				new File(workingDir, MODEL_NAME),
				parser);

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

	public static void writeMergedModel(
			File alphabetDest, File modelDest, PipelinedFnParser parser) {
		Alphabet<String> alph = parser.getAlphabet();
		ModelIO.writeAlphabet(alph, alphabetDest);
		FgModel wf = parser.getFrameIdStage().getWeights();
		FgModel wa = parser.getArgIdStage().getWeights();
		FgModel ws = parser.getArgSpanStage().getWeights();
		ModelMerger.Model<String> merged = ModelMerger.merge(
				new ModelMerger.Model<>(alph, wf),
				new ModelMerger.Model<>(alph, wa),
				new ModelMerger.Model<>(alph, ws));
		assert alph.size() == merged.alphabet.size();
		ModelIO.writeBinary(merged.getFgModel(), modelDest);
	}

	public static ModelMerger.Model<String> readMergedModel(
			File alphabetSrc, File modelSrc) {
		return new ModelMerger.Model<String>(
				ModelIO.readAlphabet(alphabetSrc),
				ModelIO.readBinary(modelSrc));
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

