package edu.jhu.hlt.fnparse.experiment;

import java.util.*;
import java.io.*;

import edu.jhu.hlt.fnparse.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.Parser.Mode;
import edu.jhu.hlt.fnparse.util.*;
import edu.jhu.hlt.fnparse.util.ArrayJobHelper.Option;

public class ParserExperiment {

	public static Mode parserMode;
	private static File alphabetModelFile;	// input
	
	public static void main(String[] args) {
		
		if(args.length != 5) {
			System.out.println("please provide:");
			System.out.println("1) a mode (e.g. \"frameId\" or \"argId\")");
			System.out.println("2) a job index (run with -1 to see options)");
			System.out.println("3) a working directory (for output files");
			System.out.println("4) an alphabet model that specifies which features to use (use AlphabetComputer)");
			System.out.println("5) a syntax mode (either \"regular\", \"none\", or \"latent\")");
			return;
		}
		System.out.println("[main] args=" + Arrays.toString(args));
		
		// mode
		String m = args[0];
		if(m.equalsIgnoreCase("frameid"))
			parserMode = Mode.FRAME_ID;
		else if(m.equalsIgnoreCase("argId") || m.equalsIgnoreCase("roleId"))
			parserMode = Mode.PIPELINE_FRAME_ARG;
		else throw new RuntimeException("unknown mode: " + m);
		
		// working directory
		File workingDir = new File(args[2]);
		if(!workingDir.isDirectory())
			workingDir.mkdirs();
		System.out.println("[main] workingDir = " + workingDir.getPath());
		
		// job index
		long start = System.currentTimeMillis();
		ArrayJobHelper ajh = new ArrayJobHelper();
		Option<Integer> nTrainLimit = ajh.addOption("nTrainLimit", Arrays.asList(50, 400, 999999));
		List<Integer> possiblePasses = parserMode == Mode.FRAME_ID
				? Arrays.asList(2, 6)
				: Arrays.asList(1, 3);
		Option<Integer> passes = ajh.addOption("passes", possiblePasses);
		Option<Integer> batchSize = ajh.addOption("batchSize", Arrays.asList(4, 40));
		Option<Double> regularizer = ajh.addOption("regularizer", Arrays.asList(1000d, 10000d, 100000d));
		Option<Boolean> useGoldFrames = null;
		if(parserMode == Mode.PIPELINE_FRAME_ARG)
			useGoldFrames = ajh.addOption("useGoldFrames", Arrays.asList(true, false));
		int jobIdx = Integer.parseInt(args[1]);
		if(jobIdx < 0) {
			System.out.println(ajh.helpString(999999));
			return;
		}
		else ajh.setConfig(jobIdx);
		System.out.println("config = " + ajh.getStoredConfig());
		
		// alphabet model
		alphabetModelFile = new File(args[3]);
		if(!alphabetModelFile.isFile())
			throw new RuntimeException(alphabetModelFile.getPath() + " is not a file\n  use AlphabetComputer to make an alphabet model");
		
		// syntax mode
		String syntaxMode = args[4];
		if(!Arrays.asList("regular", "none", "latent").contains(syntaxMode))
			throw new IllegalStateException("unknown syntax mode: " + syntaxMode);
		
		// get the data
		DataSplitter ds = new DataSplitter();
		List<FNParse> all = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		List<FNParse> trainTune = new ArrayList<FNParse>();
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> tune = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(all, trainTune, test, 0.2d, "fn15_train-test");
		ds.split(trainTune, train, tune, Math.min(75, (int) (0.15d * trainTune.size())), "fn15_train-tune");
		
		if(nTrainLimit.get() < train.size())
			train = DataUtil.reservoirSample(train, nTrainLimit.get());
		List<FNParse> trainSubset = DataUtil.reservoirSample(train, test.size());

		printMemUsage();
		System.out.printf("[main] #train=%d #tune=%d #test=%d\n", train.size(), tune.size(), test.size());
		
		// create parser
		boolean latentSyntax = "latent".equals(syntaxMode);
		boolean noSyntaxFeatures = "none".equals(syntaxMode);
		Parser parser = new Parser(alphabetModelFile);
		parser.setMode(parserMode, latentSyntax);
		parser.params.useSyntaxFeatures = !noSyntaxFeatures;
		if(latentSyntax)
			parser.params.useSyntaxFeatures = false;
		System.out.printf("[ParserExperiment] this model was read in from %s, "
				+ "and i'm assuming that this model's alphabet (size=%d) already "
				+ "includes all of the features needed to train in %s mode\n",
				alphabetModelFile.getPath(), parser.params.featIdx.size(), parserMode);

		// train
		Double lrMult = parserMode == Mode.FRAME_ID ? null : 0.05d;	// null means do auto learning rate selection
		System.out.println("[ParserExperiment] starting, lrMult=" + lrMult);
		parser.train(train, passes.get(), batchSize.get(), lrMult, regularizer.get(), true);
		System.out.printf("[ParserExperiment] after training, #features=%d\n", parser.params.featIdx.size());
		printMemUsage();

		// write parameters out as soon as possible
		parser.writeWeights(new File(workingDir, parserMode + ".weights.txt"));
		parser.writeModel(new File(workingDir, parserMode + ".model.gz"));

		// this can take a while!
		int maxNumTune = parserMode == Mode.FRAME_ID ? 9999 : 40;
		System.out.printf("[ParserExperiment] tuning on %d examples\n", Math.min(maxNumTune, tune.size()));
		parser.tune(tune, maxNumTune);
		printMemUsage();
		
		// write model again so that tuning parameters are updated
		parser.writeModel(new File(workingDir, parserMode + ".model.gz"));

		// evaluate (test data)
		List<FNParse> predicted;
		Map<String, Double> results;
		System.out.printf("[ParserExperiment] predicting on %d test examples...\n", test.size());
		if(useGoldFrames != null && useGoldFrames.get())
			predicted = parser.parseUsingGoldFrameId(test);
		else
			predicted = parser.parseWithoutPeeking(test);
		results = BasicEvaluation.evaluate(test, predicted);
		BasicEvaluation.showResults("[test] after " + passes.get() + " passes", results);
		printMemUsage();

		// evaluate (train data)
		System.out.println("[ParserExperiment] predicting on train (sub)set...");
		if(useGoldFrames != null && useGoldFrames.get())
			predicted = parser.parseUsingGoldFrameId(trainSubset);
		else
			predicted = parser.parseWithoutPeeking(trainSubset);
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

