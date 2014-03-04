package edu.jhu.hlt.fnparse.experiment;

import java.util.*;
import java.io.*;

import edu.jhu.hlt.fnparse.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser;
import edu.jhu.hlt.fnparse.util.ArrayJobHelper;
import edu.jhu.hlt.fnparse.util.ArrayJobHelper.Option;

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
//		while(instances.hasNext()) {
//			FNTagging p = instances.next();
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
		
		pruningDebug(new LexicalUnit("idea", "NN"), fi.getFrameByName("Desirable_event"));
		pruningDebug(new LexicalUnit("do", "VB"), fi.getFrameByName("Intentionally_act"));
		pruningDebug(new LexicalUnit("sooner", "RBR"), fi.getFrameByName("Time_vector"));
		pruningDebug(new LexicalUnit("several", "JJ"), fi.getFrameByName("Quantity"));
		pruningDebug(new LexicalUnit("factories", "NNS"), fi.getFrameByName("Locale_by_use"));
		
		
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
		
		ArrayJobHelper ajh = new ArrayJobHelper();
		Option<Integer> nTrainLimit = ajh.addOption("nTrainLimit", Arrays.asList(150, 999999));
		Option<Integer> batchSize = ajh.addOption("batchSize", Arrays.asList(1, 10, 100));
		Option<Double> regularizer = ajh.addOption("regularizer", Arrays.asList(1d, 3d, 10d, 30d, 100d));
		Option<Double> lrMult = ajh.addOption("lrMult", Arrays.asList(0.1d, 0.3d, 1d, 3d, 10d));
		Option<Double> lrDecay = ajh.addOption("lrDecay", Arrays.asList(1d, 0.85d, 0.7d, 0.55d));
		ajh.setConfig(args);	// options are now valid
		System.out.println("config = " + ajh.getStoredConfig());
		
		File workingDir = new File(modelDir, getDescription(ajh));
		if(!workingDir.isDirectory())
			workingDir.mkdir();
		
		// get the data
		DataSplitter ds = new DataSplitter();
		List<FNParse> all = DataUtil.iter2list(FileFrameInstanceProvider.fn15trainFIP.getParsedSentences());
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(all, train, test, 0.2d, "fn15_train");
		List<FNParse> trainSubset = DataUtil.reservoirSample(train, 100);
		printMemUsage();

		if(nTrainLimit.get() < train.size())
			train = DataUtil.reservoirSample(train, nTrainLimit.get());
		
		final int passesPerEpoch = 2;
		double lrMultRunning = lrMult.get();
		List<FNParse> predicted;
		Map<String, Double> results;
		Parser parser = new Parser();
		for(int epoch=0; epoch<10; epoch++) {
			System.out.printf("[ParserExperiment] starting epoch %d, lrMult=%.3f\n", epoch, lrMultRunning);
			parser.train(train, passesPerEpoch, batchSize.get(), lrMultRunning, regularizer.get());
			System.out.printf("[ParserExperiment] after training in epoch %d, #features=%d\n",
				epoch, parser.params.featIdx.size());
			printMemUsage();

			System.out.println("[ParserExperiment] predicting on test set...");
			predicted = parser.parseWithoutPeeking(test);
			results = BasicEvaluation.evaluate(test, predicted);
			BasicEvaluation.showResults("[test] after " + (epoch+1) + " epochs", results);
			printMemUsage();
			
			System.out.println("[ParserExperiment] predicting on train set...");
			predicted = parser.parseWithoutPeeking(trainSubset);
			results = BasicEvaluation.evaluate(trainSubset, predicted);
			BasicEvaluation.showResults("[train] after " + (epoch+1) + " epochs", results);
			printMemUsage();
			
			parser.writeoutWeights(new File(workingDir, "weights.epoch" + (epoch+1) + ".txt"));
			
			lrMultRunning *= lrDecay.get();
		}
	}
	
	public static void mainOld(String[] args) {

		if(!modelDir.isDirectory())
			modelDir.mkdir();
		
		// get the data
		DataSplitter ds = new DataSplitter();
		List<FNParse> all = DataUtil.iter2list(FileFrameInstanceProvider.fn15trainFIP.getParsedSentences());
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(all, train, test, 0.2d, "fn15_train");
//		train = getSuitableTrainingExamples(train);	// get rid of nasty examples
//		test = getSuitableTrainingExamples(test);	// get rid of nasty examples
		
		int nTrain = 1000;
		int nTest = 100;
		//if(hurryUp)  {
		train = DataUtil.reservoirSample(train, nTrain);
		test = DataUtil.reservoirSample(test, nTest);
		//}
		List<FNParse> trainSubset = DataUtil.reservoirSample(train, nTest);
		printMemUsage();
		
		// train and evaluate along the way
		List<FNParse> predicted;
		Map<String, Double> results;
		Parser parser = new Parser();
		for(int epoch=0; epoch<8; epoch++) {
			System.out.println("[ParserExperiment] starting epoch " + epoch);
			int passes = 1;
			int batchSize = 1;
			double lrMult = 4d / (5d + epoch);
			double regularizerMult = 2d;
			parser.train(train, passes, batchSize, lrMult, regularizerMult);
			System.out.printf("[ParserExperiment] after training in epoch %d, #features=%d\n",
				epoch, parser.params.featIdx.size());
			printMemUsage();

			System.out.println("[ParserExperiment] predicting on test set...");
			predicted = parser.parseWithoutPeeking(test);
			results = BasicEvaluation.evaluate(test, predicted);
			BasicEvaluation.showResults("[test] after " + (epoch+1) + " epochs", results);
			printMemUsage();
			
			System.out.println("[ParserExperiment] predicting on train set...");
			predicted = parser.parseWithoutPeeking(trainSubset);
			results = BasicEvaluation.evaluate(trainSubset, predicted);
			BasicEvaluation.showResults("[train] after " + (epoch+1) + " epochs", results);
			printMemUsage();
			parser.writeoutWeights(new File(modelDir, "weights.epoch" + (epoch+1) + ".txt"));
		}
	}
	
	/** @deprecated */
	public static List<FNParse> getSuitableTrainingExamples(List<FNParse> train) {
		final int maxArgWidth = 10;
		final int maxTargetWidth = 3;
		final int maxSentLen = 20;
		List<FNParse> buf = new ArrayList<FNParse>();
		outer: for(FNParse t : train) {
			for(FrameInstance fi : t.getFrameInstances()) {
				if(fi.getSentence().size() > maxSentLen)
					continue outer;
				if(fi.getTarget().width() > maxTargetWidth)
					continue outer;
				for(int k=0; k<fi.numArguments(); k++)
					if(fi.getArgument(k).width() > maxArgWidth)
						continue outer;
			}
			buf.add(t);
		}
		return buf;
	}
}

