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
		lexInstances = observedTriggers(FileFrameInstanceProvider.fn15lexFIP.getParsedSentences(), missed);
		System.out.printf("[pruningDebug] triggers for %s in lex/p examples (%d):", missed.getName(), lexInstances.size());
		for(LexicalUnit lu : lexInstances)
			System.out.print(" " + lu.getFullString());
		System.out.println();
		
		lexInstances = observedTriggers(FileFrameInstanceProvider.fn15lexFIP.getTaggedSentences(), missed);
		System.out.printf("[pruningDebug] triggers for %s in lex/t examples (%d):", missed.getName(), lexInstances.size());
		for(LexicalUnit lu : lexInstances)
			System.out.print(" " + lu.getFullString());
		System.out.println();
		
		lexInstances = observedTriggers(FileFrameInstanceProvider.fn15trainFIP.getParsedSentences(), missed);
		System.out.printf("[pruningDebug] triggers for %s in train examples (%d):", missed.getName(), lexInstances.size());
		for(LexicalUnit lu : lexInstances)
			System.out.print(" " + lu.getFullString());
		System.out.println();
		
		System.out.println();
	}
	
	public static void mainDebugging(String[] args) {
		FrameIndex fi = FrameIndex.getInstance();
		pruningDebug(new LexicalUnit("toxins", "NNS"), fi.getFrameByName("Toxic_substance"));
		pruningDebug(new LexicalUnit("Potential", "JJ"), fi.getFrameByName("Capability"));
		pruningDebug(new LexicalUnit("of", "IN"), fi.getFrameByName("Partitive"));
		pruningDebug(new LexicalUnit("representatives", "NNS"), fi.getFrameByName("Leadership"));
		/*
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU Potential.JJ> did not include the gold frame: <Frame 149 Capability>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU of.IN> did not include the gold frame: <Frame 665 Partitive>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU representatives.NNS> did not include the gold frame: <Frame 552 Leadership>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU of.IN> did not include the gold frame: <Frame 155 Causation>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU Biological.NNP> did not include the gold frame: <Frame 1010 Weapon>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU applications.NNS> did not include the gold frame: <Frame 984 Using>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU procurement.NN> did not include the gold frame: <Frame 442 Getting>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU toxins.NNS> did not include the gold frame: <Frame 959 Toxic_substance>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU nineteenth.NN> did not include the gold frame: <Frame 651 Ordinal_numbers>
WARNING: frame filtering heuristic didn't extract <Frame 397 Experiencer_focus> for [dissatisfied]
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU signatory.JJ> did not include the gold frame: <Frame 871 Sign_agreement>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU at.IN> did not include the gold frame: <Frame 566 Locative_relation>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU rode.VBD> did not include the gold frame: <Frame 929 Surviving>
[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidiate set of frames for <LU on.IN> did not include the gold frame: <Frame 566 Locative_relation>
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
	
	public static void main(String[] args) {
		
		if(false) {
			mainDebugging(args);
			return;
		}
		
		ArrayJobHelper ajh = new ArrayJobHelper();
		Option<Integer> nTrainLimit = ajh.addOption("nTrainLimit", Arrays.asList(150, 999999));
		Option<Integer> batchSize = ajh.addOption("batchSize", Arrays.asList(1, 3, 10, 30, 100, 300, 1000));
		Option<Double> lrMult = ajh.addOption("lrMult", Arrays.asList(0.1d, 0.3d, 1d, 3d, 10d));
		Option<Double> lrDecay = ajh.addOption("lrDecay", Arrays.asList(1d, 0.9d, 0.8d, 0.7d, 0.6d, 0.5d));
		ajh.setConfig(args);	// options are now valid
		System.out.println("config = " + ajh.getStoredConfig());
		
		File workingDir = new File(modelDir, getDescription(ajh));
		if(!workingDir.isDirectory())
			workingDir.mkdir();
		
		// get the data
		DataSplitter ds = new DataSplitter();
		List<FNParse> all = FileFrameInstanceProvider.fn15trainFIP.getParsedSentences();
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
			parser.train(train, passesPerEpoch, batchSize.get(), lrMultRunning);
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
		List<FNParse> all = FileFrameInstanceProvider.fn15trainFIP.getParsedSentences();
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(all, train, test, 0.2d, "fn15_train");
		train = getSuitableTrainingExamples(train);	// get rid of nasty examples
		test = getSuitableTrainingExamples(test);	// get rid of nasty examples
		
		int nTrain = 750;
		int nTest = 100;
		//if(hurryUp) {
		train = DataUtil.reservoirSample(train, nTrain);
		test = DataUtil.reservoirSample(test, nTest);
		//}
		List<FNParse> trainSubset = DataUtil.reservoirSample(train, nTest);
		printMemUsage();
		
		// train and evaluate along the way
		List<FNParse> predicted;
		Map<String, Double> results;
		Parser parser = new Parser();
		for(int epoch=0; epoch<15; epoch++) {
			System.out.println("[ParserExperiment] starting epoch " + epoch);
			int passes = 2;
			int batchSize = 1;
			double lrMult = 10d / (2d + epoch);
			parser.train(train, passes, batchSize, lrMult);
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

