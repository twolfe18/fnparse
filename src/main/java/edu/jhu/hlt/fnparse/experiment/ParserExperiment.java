package edu.jhu.hlt.fnparse.experiment;

import java.util.*;
import java.io.*;

import edu.jhu.hlt.fnparse.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.inference.misc.Parser;
import edu.jhu.hlt.fnparse.inference.misc.Parser.Mode;
import edu.jhu.hlt.fnparse.util.*;
import edu.jhu.hlt.fnparse.util.ArrayJobHelper.Option;
import edu.jhu.hlt.fnparse.util.Timer;

public class ParserExperiment {

	public static final boolean hurryUp = true;
	public static final File modelDir = new File("experiments/targetId");
	public static List<? extends FNTagging> fnTrain, fnLex;
	
	public static void main(String[] args) {
		
//		fnTrain = DataUtil.iter2list(FileFrameInstanceProvider.fn15trainFIP.getParsedSentences());
//		fnLex = DataUtil.iter2list(FileFrameInstanceProvider.fn15lexFIP.getParsedOrTaggedSentences());
//		mainDebugging(args);
//		fnTrain = null;
//		fnLex = null;
		
//		mainOld(args);
		
		mainNew(args);
	}
	
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
	
	public static void pruningDebug(LexicalUnit head, Frame missed) {
		
		Set<LexicalUnit> lexInstances;
		System.out.printf("[pruningDebug] did not recall the frame %s given the trigger %s\n",
				missed.getName(), head.getFullString());
		
		// all LUs for this frame
		for(int i=0; i<missed.numLexicalUnits(); i++)
			System.out.printf("[pruningDebug] %s.LU[%d]=%s\n", missed.getName(), i, missed.getLexicalUnit(i));
		
		// all examples lex for this frame
		lexInstances = observedTriggers(fnLex, missed);
		System.out.printf("[pruningDebug] triggers for %s in lex/p examples (%d):", missed.getName(), lexInstances.size());
		for(LexicalUnit lu : lexInstances)
			System.out.print(" " + lu.getFullString());
		System.out.println();
		
		lexInstances = observedTriggers(fnLex, missed);
		System.out.printf("[pruningDebug] triggers for %s in lex/t examples (%d):", missed.getName(), lexInstances.size());
		for(LexicalUnit lu : lexInstances)
			System.out.print(" " + lu.getFullString());
		System.out.println();
		
		lexInstances = observedTriggers(fnTrain, missed);
		System.out.printf("[pruningDebug] triggers for %s in train examples (%d):", missed.getName(), lexInstances.size());
		for(LexicalUnit lu : lexInstances)
			System.out.print(" " + lu.getFullString());
		System.out.println();
		
		System.out.println();
	}
	
	public static void mainDebugging(String[] args) {
		FrameIndex fi = FrameIndex.getInstance();
		
		// old
//		pruningDebug(new LexicalUnit("toxins", "NNS"), fi.getFrameByName("Toxic_substance"));
//		pruningDebug(new LexicalUnit("Potential", "JJ"), fi.getFrameByName("Capability"));
//		pruningDebug(new LexicalUnit("of", "IN"), fi.getFrameByName("Partitive"));
//		pruningDebug(new LexicalUnit("representatives", "NNS"), fi.getFrameByName("Leadership"));
		
		pruningDebug(new LexicalUnit("idea", "NN"), fi.getFrame("Desirable_event"));
		pruningDebug(new LexicalUnit("do", "VB"), fi.getFrame("Intentionally_act"));
		pruningDebug(new LexicalUnit("sooner", "RBR"), fi.getFrame("Time_vector"));
		pruningDebug(new LexicalUnit("several", "JJ"), fi.getFrame("Quantity"));
		pruningDebug(new LexicalUnit("factories", "NNS"), fi.getFrame("Locale_by_use"));
		
		
		/*
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidate set of frames for <LU idea.NN> did not include the gold frame: <Frame 306 Desirable_event>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidate set of frames for <LU do.VB> did not include the gold frame: <Frame 525 Intentionally_act>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidate set of frames for <LU sooner.RBR> did not include the gold frame: <Frame 954 Time_vector>
		 */
	}
	
	public static void printMemUsage() {
		double used = Runtime.getRuntime().totalMemory();
		used /= 1024d * 1024d * 1024d;
		double free = Runtime.getRuntime().maxMemory();
		free /= 1024d * 1024d * 1024d;
		System.out.printf("[ParserExperiment] using %.2f GB, %.2f GB free\n", used, free);
	}
	
	private static String getDescription(ArrayJobHelper ajh) {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for(Map.Entry<String, Object> x : ajh.getStoredConfig().entrySet()) {
			if(!first) sb.append("_");
			first = false;
			sb.append(x.getKey());
			sb.append("=");
			sb.append(x.getValue().toString());
		}
		return sb.toString();
	}
	
	public static void mainNew(String[] args) {
		
		System.out.println("[main] args=" + Arrays.toString(args));
		ArrayJobHelper ajh = new ArrayJobHelper();
		Option<Integer> nTrainLimit = ajh.addOption("nTrainLimit", Arrays.asList(300, 999999));
		Option<Integer> batchSize = ajh.addOption("batchSize", Arrays.asList(1, 10, 100));
		Option<Double> regularizer = ajh.addOption("regularizer", Arrays.asList(0.2, 1d, 5d));
		Option<Double> lrMult = ajh.addOption("lrMult", Arrays.asList(0.1d, 1d, 10d));
		Option<Integer> passes = ajh.addOption("passes", Arrays.asList(2, 5, 10));
		Option<Double> recallBias = ajh.addOption("recallBias", Arrays.asList(1d, 3d));
		ajh.setConfig(args);	// options are now valid
		System.out.println("config = " + ajh.getStoredConfig());
		
		//File workingDir = new File(modelDir, getDescription(ajh));
		File workingDir = new File(modelDir, args[0]);
		if(!workingDir.isDirectory())
			workingDir.mkdir();
		System.out.println("[main] workingDir = " + workingDir.getPath());
		
		// get the data
		DataSplitter ds = new DataSplitter();
		List<FNParse> all = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(all, train, test, 0.2d, "fn15_train");
		
		printMemUsage();

		if(nTrainLimit.get() < train.size())
			train = DataUtil.reservoirSample(train, nTrainLimit.get());
		List<FNParse> trainSubset = DataUtil.reservoirSample(train, test.size());
		
		System.out.printf("[main] #train=%d #test=%d\n", train.size(), test.size());
		
		List<FNParse> predicted;
		Map<String, Double> results;
		boolean debug = false;
		Parser parser = new Parser(Mode.FRAME_ID, debug);
		parser.params.frameDecoder.setRecallBias(recallBias.get());

		System.out.println("[ParserExperiment] following statistics are for the train subset:");
		//parser.computeStatistcs(trainSubset);
		printMemUsage();

		System.out.printf("[ParserExperiment] starting, lrMult=%.3f\n", lrMult.get());
		parser.train(train, passes.get(), batchSize.get(), lrMult.get(), regularizer.get());
		System.out.printf("[ParserExperiment] after training, #features=%d\n", parser.params.featIdx.size());
		printMemUsage();

		System.out.println("[ParserExperiment] predicting on test set...");
		predicted = (List<FNParse>) (Object) parser.parseWithoutPeeking(test);	// double cast ftw!
		results = BasicEvaluation.evaluate(test, predicted);
		BasicEvaluation.showResults("[test] after " + passes.get() + " passes", results);
		printMemUsage();

		System.out.println("[ParserExperiment] predicting on train set...");
		predicted = (List<FNParse>) (Object) parser.parseWithoutPeeking(trainSubset);	// double case ftw!
		results = BasicEvaluation.evaluate(trainSubset, predicted);
		BasicEvaluation.showResults("[train] after " + passes.get() + " passes", results);
		printMemUsage();

		parser.writeWeights(new File(workingDir, "weights.frameId.txt"));
		parser.writeModel(new File(workingDir, "model.frameId.ser"));
	}
	
	public static void mainOld(String[] args) {

		if(!modelDir.isDirectory())
			modelDir.mkdir();
		
		// get the data
		DataSplitter ds = new DataSplitter();
		List<FNParse> all = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(all, train, test, 0.2d, "fn15_train");
		//train = getSuitableTrainingExamples(train);	// get rid of nasty examples
		//test = getSuitableTrainingExamples(test);	// get rid of nasty examples
		
		boolean eval = true;
		boolean debug = false;
		
		int nTrain = 200;
		int nTest = 60;
		train = DataUtil.reservoirSample(train, nTrain);
		test = DataUtil.reservoirSample(test, nTest);
		List<FNParse> trainSubset = DataUtil.reservoirSample(train, nTest);
		printMemUsage();
		
		Timer trainTimer = new Timer("trainTimer", 1);
		Timer decodeTimer = new Timer("decodeTimer", 1);
		
		// train and evaluate along the way
		int trainSentencesProcessed = 0;
		List<FNParse> predicted;
		Map<String, Double> results;
		Parser parser = new Parser(Mode.FRAME_ID, debug);
		parser.params.frameDecoder.setRecallBias(1.5d);
		
		//parser.computeStatistcs(train);
		
		for(int epoch=0; epoch<2; epoch++) {
			System.out.println("[ParserExperiment] starting epoch " + epoch);
			int passes = 2;
			int batchSize = 1;
			double lrMult = 4d / (5d + epoch);
			double regularizerMult = 1d;
			trainTimer.start();
			parser.train(train, passes, batchSize, lrMult, regularizerMult);
			trainTimer.stop();
			trainSentencesProcessed += train.size() * passes;
			System.out.printf("[ParserExperiment] after training in epoch %d, #features=%d\n",
				epoch, parser.params.featIdx.size());
			printMemUsage();

			if(eval) {
				System.out.println("[ParserExperiment] predicting on test set...");
				decodeTimer.start();
				predicted = (List<FNParse>) (Object) parser.parseWithoutPeeking(test);
				decodeTimer.stop();
				results = BasicEvaluation.evaluate(test, predicted);
				BasicEvaluation.showResults("[test] after " + (epoch+1) + " epochs", results);
				printMemUsage();

				System.out.println("[ParserExperiment] predicting on train set...");
				decodeTimer.start();
				predicted = (List<FNParse>) (Object) parser.parseWithoutPeeking(trainSubset);
				decodeTimer.stop();
				results = BasicEvaluation.evaluate(trainSubset, predicted);
				BasicEvaluation.showResults("[train] after " + (epoch+1) + " epochs", results);
				printMemUsage();
				parser.writeWeights(new File(modelDir, "weights.epoch" + (epoch+1) + ".txt"));
			}
			
			double secPerInst = trainTimer.totalTimeInSec() / trainSentencesProcessed;
			System.out.println("time to train on 1000 sentences: " + (1000d * secPerInst));
		}
		System.out.println("total train time " + trainTimer.totalTimeInSec());
		System.out.println("total decode time " + decodeTimer.totalTimeInSec());
	}
	
	/** @deprecated */
	public static List<FNParse> getSuitableTrainingExamples(List<FNParse> train) {
		final int maxArgWidth = 999999;
		final int maxTargetWidth = 999999;
		final int maxSentLen = 99;
		int total = 0;
		List<FNParse> buf = new ArrayList<FNParse>();
		
		int[] lengthHist = new int[50];
		
		outer: for(FNParse t : train) {
			Sentence s = t.getSentence();
			if(s.size() >= lengthHist.length)
				lengthHist[0]++;
			else
				lengthHist[s.size()]++;
			for(FrameInstance fi : t.getFrameInstances()) {
				total++;
				if(s.size() > maxSentLen)
					continue outer;
				if(fi.getTarget().width() > maxTargetWidth)
					continue outer;
				for(int k=0; k<fi.numArguments(); k++)
					if(fi.getArgument(k).width() > maxArgWidth)
						continue outer;
			}
			buf.add(t);
		}
		System.out.printf("[getSuitableTrainingExamples] kept %d of %d FrameInstances (%.1f %%)\n",
				buf.size(), total, (100d*buf.size())/total);
		
		System.out.println("histogram of lengths (cutoff is currently " + maxSentLen + ")");
		for(int i=0; i<lengthHist.length; i++) {
			if(i == 0)
				System.out.printf(">%d\t%d\n", lengthHist.length-1, lengthHist[lengthHist.length-1]);
			else
				System.out.printf(" %d\t%d\n", i, lengthHist[i]);
		}
		System.out.println();
		
		return buf;
	}
}

