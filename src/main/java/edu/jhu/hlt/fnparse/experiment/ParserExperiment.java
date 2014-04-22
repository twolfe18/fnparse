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

	public static final File modelDir = new File("experiments/targetId");
	
	public static Set<LexicalUnit> observedTriggers(Collection<? extends FNTagging> instances, Frame f) {
		Set<LexicalUnit> lexInstance = new HashSet<LexicalUnit>();
		for(FNTagging p : instances) {
			for(FrameInstance fi : p.getFrameInstances()) {
				Span target = fi.getTarget();
				if(fi.getFrame().equals(f) && target.width() == 1)
					lexInstance.add(fi.getSentence().getLU(target.start));
			}
		}
		return lexInstance;
	}
	
	public static void printMemUsage() {
		double used = Runtime.getRuntime().totalMemory();
		used /= 1024d * 1024d * 1024d;
		double free = Runtime.getRuntime().maxMemory();
		free /= 1024d * 1024d * 1024d;
		System.out.printf("[ParserExperiment] using %.2f GB, %.2f GB free\n", used, free);
	}
	
	public static void main(String[] args) {
		
		long start = System.currentTimeMillis();
		System.out.println("[main] args=" + Arrays.toString(args));
		ArrayJobHelper ajh = new ArrayJobHelper();
		Option<Integer> nTrainLimit = ajh.addOption("nTrainLimit", Arrays.asList(100, 400, 1600, 999999));
		Option<Integer> passes = ajh.addOption("passes", Arrays.asList(2, 10));
		Option<Integer> batchSize = ajh.addOption("batchSize", Arrays.asList(10, 100));
		Option<Double> regularizer = ajh.addOption("regularizer", Arrays.asList(300d, 1000d, 3000d, 10000d, 30000d));
		Option<String> syntaxMode = ajh.addOption("syntaxMode", Arrays.asList("regular", "noSyntax", "latentSyntax"));
		ajh.setConfig(args);	// options are now valid
		System.out.println("config = " + ajh.getStoredConfig());
		
		File workingDir = new File(modelDir, args[0]);
		if(!workingDir.isDirectory())
			workingDir.mkdir();
		System.out.println("[main] workingDir = " + workingDir.getPath());
		
		// get the data
		DataSplitter ds = new DataSplitter();
		List<FNParse> all = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		List<FNParse> trainTune = new ArrayList<FNParse>();
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> tune = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(all, trainTune, test, 0.2d, "fn15_train-test");
		ds.split(trainTune, train, tune, Math.min(75, (int) (0.2d * trainTune.size())), "fn15_train-tune");
		
		printMemUsage();

		if(nTrainLimit.get() < train.size())
			train = DataUtil.reservoirSample(train, nTrainLimit.get());
		List<FNParse> trainSubset = DataUtil.reservoirSample(train, test.size());
		
		System.out.printf("[main] #train=%d #tune=%d #test=%d\n", train.size(), tune.size(), test.size());
		
		List<FNParse> predicted;
		Map<String, Double> results;
		final double recallBias = 1.5d;
		final double lrMult = 0.1d;
		final boolean debug = false;

		Mode parserMode = Mode.FRAME_ID;
		Parser parser = null;
		if("regular".equals(syntaxMode.get())) {
			parser = new Parser(parserMode, false, debug);
			parser.params.useSyntaxFeatures = true;
		}
		else if("noSyntax".equals(syntaxMode.get())) {
			parser = new Parser(parserMode, false, debug);
			parser.params.useSyntaxFeatures = false;
		}
		else if("latentSyntax".equals(syntaxMode.get())) {
			parser = new Parser(parserMode, true, debug);
			parser.params.useSyntaxFeatures = false;
		}
		else throw new RuntimeException("mode=" + syntaxMode.get());

		parser.params.frameDecoder.setRecallBias(recallBias);

		System.out.println("[ParserExperiment] following statistics are for the train subset:");
		//parser.computeStatistcs(trainSubset);
		printMemUsage();

		System.out.printf("[ParserExperiment] starting, lrMult=%.3f\n", lrMult);
		parser.train(train, passes.get(), batchSize.get(), lrMult, regularizer.get());
		System.out.printf("[ParserExperiment] after training, #features=%d\n", parser.params.featIdx.size());
		printMemUsage();

		System.out.printf("[ParserExperiment] tuning on %d examples\n", tune.size());
		parser.tune(tune);
		printMemUsage();

		System.out.println("[ParserExperiment] predicting on test set...");
		predicted = parser.parseWithoutPeeking(test);
		results = BasicEvaluation.evaluate(test, predicted);
		BasicEvaluation.showResults("[test] after " + passes.get() + " passes", results);
		printMemUsage();

		System.out.println("[ParserExperiment] predicting on train (sub)set...");
		predicted = parser.parseWithoutPeeking(trainSubset);
		results = BasicEvaluation.evaluate(trainSubset, predicted);
		BasicEvaluation.showResults("[train] after " + passes.get() + " passes", results);
		printMemUsage();

		parser.writeWeights(new File(workingDir, "weights.frameId.txt"));
		parser.writeModel(new File(workingDir, "model.frameId.ser.gz"));
		System.out.printf("[ParserExperiment] done, took %.1f minutes\n", (System.currentTimeMillis() - start) / (1000d * 60));
	}
	
}

