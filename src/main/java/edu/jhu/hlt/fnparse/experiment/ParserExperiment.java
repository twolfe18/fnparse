package edu.jhu.hlt.fnparse.experiment;

import java.util.*;
import java.io.*;

import edu.jhu.hlt.fnparse.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;
import edu.jhu.hlt.fnparse.util.*;
import edu.jhu.hlt.fnparse.util.ArrayJobHelper.Option;
import edu.jhu.hlt.optimize.functions.L2;

/**
 * This class runs experiments where ther are two types of options:
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

	public static void main(String[] args) throws IOException {

		if(args.length != 5) {
			System.out.println("Please provide:");
			System.out.println("1) A mode. Possible values:");
			System.out.println("     \"frameId\" will train and test just the frame identification stage");
			System.out.println("     \"argId\" will use gold frames and train and test the argId stage(s)");
			System.out.println("2) A job index for sweep parameters (run with -1 to see options)");
			System.out.println("3) A working directory (for output files)");
			System.out.println("4) An alphabet of pre-computed feature names (use AlphabetComputer)");
			System.out.println("   This may be the string \"none\" if you want one to be computed (slow)");
			System.out.println("5) A syntax mode. Possible values:");
			System.out.println("     \"none\" means that features have no access to syntax information");
			System.out.println("     \"regular\" means that 1-best parses are used for features");
			System.out.println("     \"latent\" means that latent syntax is jointly reasoned about with prediction");
			return;
		}
		System.out.println("[main] args=" + Arrays.toString(args));

		final String mode = args[0];
		final int jobIdx = Integer.parseInt(args[1]);
		final File workingDir = new File(args[2]);
		//final File alphabetModelFile = new File(args[3]);
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
		System.out.println("[main] workingDir = " + workingDir.getPath());

		// Set up array job configurations
		ArrayJobHelper ajh = new ArrayJobHelper();
		Option<Integer> nTrainLimit = ajh.addOption("nTrainLimit", Arrays.asList(50, 400, 999999));
		List<Integer> possiblePasses = "frameId".equals(mode)
				? Arrays.asList(2, 6)
				: Arrays.asList(1, 3);
		Option<Integer> passes = ajh.addOption("passes", possiblePasses);
		Option<Integer> batchSize = ajh.addOption("batchSize", Arrays.asList(4, 40));
		Option<Double> regularizer = ajh.addOption("regularizer", Arrays.asList(1000d, 10000d, 100000d));

		// TODO add back the part of the experiment where we predict frames and
		// args (argId mode right now just takes gold frames).
		//Option<Boolean> useGoldFrames = ajh.addOption("useGoldFrames", Arrays.asList(true, false));

		// Choose an array job configuration
		if(jobIdx < 0) {
			System.out.println(ajh.helpString(999));
			return;
		}
		else ajh.setConfig(jobIdx);
		System.out.println("config = " + ajh.getStoredConfig());

		// Get the data
		DataSplitter ds = new DataSplitter();
		List<FNParse> all = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		List<FNParse> trainTune = new ArrayList<FNParse>();
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> tune = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(all, trainTune, test, 0.2d, "fn15_train-test");
		int nTest = Math.min(75, (int) (0.15d * trainTune.size()));
		ds.split(trainTune, train, tune, nTest, "fn15_train-tune");

		if(nTrainLimit.get() < train.size())
			train = DataUtil.reservoirSample(train, nTrainLimit.get(), new Random(9001));

		printMemUsage();
		System.out.printf("[main] #train=%d #tune=%d #test=%d\n",
				train.size(), tune.size(), test.size());

		// Create parser
		boolean latentSyntax = "latent".equals(syntaxMode);
		boolean noSyntaxFeatures = "none".equals(syntaxMode);
		PipelinedFnParser parser = new PipelinedFnParser();
		if ("none".equals(featureAlphabet)) {
			int maxMinutes = 45;
			int maxFeatures = 15_000_000;
			parser.computeAlphabet(train, maxMinutes, maxFeatures);
		} else {
			parser.setAlphabet(ModelIO.readAlphabet(new File(featureAlphabet)));
		}
		parser.getParams().useSyntaxFeatures = !noSyntaxFeatures;
		if(latentSyntax)
			parser.getParams().useSyntaxFeatures = false;
		System.out.printf("[ParserExperiment] this model was read in from %s, "
				+ "and i'm assuming that this model's alphabet (size=%d) "
				+ "already includes all of the features needed to train in %s "
				+ "mode\n",
				featureAlphabet, parser.getParams().getFeatureAlphabet(), mode);

		// Train
		// null means do auto learning rate selection
		Double lrMult = "frameId".equals(mode) ? null : 0.05d;
		System.out.println("[ParserExperiment] starting, lrMult=" + lrMult);
		if ("frameId".equals(mode)) {
			parser.getFrameIdParams().batchSize = batchSize.get();
			parser.getFrameIdParams().passes = passes.get();
			parser.getFrameIdParams().regularizer = new L2(regularizer.get());
		} else {
			parser.getArgIdParams().batchSize = batchSize.get();
			parser.getArgIdParams().passes = passes.get();
			parser.getArgIdParams().regularizer = new L2(regularizer.get());
		}
		parser.train(train);
		System.out.printf("[ParserExperiment] after training, #features=%d\n",
				parser.getAlphabet().size());
		printMemUsage();

		// Write parameters out as soon as possible
		parser.writeModel(new File(workingDir, mode + ".model.gz"));
		// TODO write out a human-readable version of the models

		// Evaluate (test data)
		List<FNParse> predicted;
		Map<String, Double> results;
		int maxTestEval = 100;
		List<FNParse> testSubset = test.size() > maxTestEval ? DataUtil.reservoirSample(test, maxTestEval, parser.getParams().rand) : test;
		System.out.printf("[ParserExperiment] predicting on %d test examples...\n", testSubset.size());
		predicted = parser.predictWithoutPeaking(testSubset);
		results = BasicEvaluation.evaluate(testSubset, predicted);
		BasicEvaluation.showResults("[test] after " + passes.get() + " passes", results);
		printMemUsage();

		// evaluate (train data)
		int maxTrainEval = 150;
		List<FNParse> trainSubset = train.size() > maxTrainEval ? DataUtil.reservoirSample(train, maxTrainEval, parser.getParams().rand) : train;
		System.out.println("[ParserExperiment] predicting on train (sub)set...");
		predicted = parser.predictWithoutPeaking(trainSubset);
		results = BasicEvaluation.evaluate(trainSubset, predicted);
		BasicEvaluation.showResults("[train] after " + passes.get() + " passes", results);
		printMemUsage();

		System.out.printf("[ParserExperiment] done, took %.1f minutes\n", (System.currentTimeMillis() - start) / (1000d * 60));
	}

	
	public static void printMemUsage() {
		double used = Runtime.getRuntime().totalMemory();
		used /= 1024d * 1024d * 1024d;
		double free = Runtime.getRuntime().maxMemory();
		free /= 1024d * 1024d * 1024d;
		free -= used;
		System.out.printf("[ParserExperiment] using %.2f GB, %.2f GB free\n", used, free);
	}
	
}

