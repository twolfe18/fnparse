package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.ModelIO;

public class ParserTests {

	public static FNParse makeDummyParse() {
		boolean verbose = false;

		String[] tokens  = new String[] {"The", "fox", "quickly", "jumped", "over", "the", "fence"};
		String[] lemmas = new String[] {"The", "fox", "quickly", "jump", "over", "the", "fence"};
		String[] pos     = new String[] {"DT",  "NN",    "RB",  "VBD",    "IN",   "DT",  "NN"};
		int[] gov        = new int[]    {1,     3,       3,     -1,       3,      6,     4};
		String[] depType = new String[] {"G",   "G",     "G",   "G",      "G",    "G",   "G"};
		Sentence s = new Sentence("test", "sent1", tokens, pos, lemmas, gov, depType);

		FrameIndex frameIdx = FrameIndex.getInstance();
		List<FrameInstance> instances = new ArrayList<FrameInstance>();

		Frame speed = frameIdx.getFrame("Speed");
		//System.out.println("speedArgs: " + Arrays.toString(speed.getRoles()));
		Span[] speedArgs = new Span[speed.numRoles()];
		Arrays.fill(speedArgs, Span.nullSpan);
		speedArgs[0] = Span.getSpan(0, 2);	// Entity
		FrameInstance speedInst = FrameInstance.newFrameInstance(speed, Span.widthOne(2), speedArgs, s);
		instances.add(speedInst);
		if(verbose)
			System.out.println("[setupDummyParse] adding instance of " + speed);

		Frame jump = frameIdx.getFrame("Self_motion");
		//System.out.println("speedArgs: " + Arrays.toString(jump.getRoles()));
		Span[] jumpArgs = new Span[jump.numRoles()];
		Arrays.fill(jumpArgs, Span.nullSpan);
		jumpArgs[0] = Span.getSpan(0, 2);	// Self_mover
		jumpArgs[2] = Span.getSpan(4, 7);	// Path
		FrameInstance jumpInst = FrameInstance.newFrameInstance(jump, Span.widthOne(3), jumpArgs, s);
		instances.add(jumpInst);
		if(verbose)
			System.out.println("[setupDummyParse] adding instance of " + jump);

		return new FNParse(s, instances);
	}

	@Before
	public void setupLogs() {
		TestingUtil.silenceLogs();
	}

	/**
	 * Make sure that we can (over)fit the dummy sentence.
	 */
	@Test
	public void basic() {
		FNParse p = makeDummyParse();

		// made by toydata/make-debug-data-from-sentence-id.sh
		//FNParse p = new FileFrameInstanceProvider(
		//				new File("toydata/fn15-fulltext.frames.train.dipanjan.debug"),
		//				new File("toydata/fn15-fulltext.conll.train.dipanjan.debug"))
		//	.getParsedSentences().next();

		//FNParse p = FNIterFilters.findBySentenceId(
		//		FileFrameInstanceProvider.debugFIP.getParsedSentences(),
		//		"FNFUTXT1274826");

		PipelinedFnParser parser = train(p);
		checkGoodPerf(parser, p, 1d, 1d, true);
		serializeWeights(parser, new File("saved-models/testing"), "basic");
		checkGoodPerf(serializeAndDeserialize(parser), p, 1d, 1d, true);
	}

	public static void serializeWeights(
			PipelinedFnParser parser, File directory, String tag) {
		System.out.printf("[serializeWeights] saving a model tagged as %s in %s\n", tag, directory.getPath());
		boolean outputZeroFeatures = false;
		ModelIO.writeHumanReadable(
				parser.getFrameIdWeights(), parser.getAlphabet(),
				new File(directory, "weights.frameId." + tag + ".txt"),
				outputZeroFeatures);
		ModelIO.writeHumanReadable(
				parser.getArgIdWeights(), parser.getAlphabet(),
				new File(directory, "weights.argId." + tag + ".txt"),
				outputZeroFeatures);
		ModelIO.writeHumanReadable(
				parser.getArgSpanWeights(), parser.getAlphabet(),
				new File(directory, "weights.argSpan." + tag + ".txt"),
				outputZeroFeatures);
	}

	/**
	 * For every sentence, we should be able to train and predict with perfect
	 * accuracy on that sentence. This ensures that our model can (over)fit all
	 * of the data.
	 */
	@Test
	public void zfuzz() {
		//List<FNParse> parses = DataUtil.iter2list(
		//      FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		List<FNParse> parses = DataUtil.iter2list(
				FileFrameInstanceProvider.debugFIP.getParsedSentences());
		parses = TestingUtil.filterOutExamplesThatCantBeFit(parses);
		//parses = DataUtil.reservoirSample(parses, 30, new Random(9001));
		for(FNParse p : parses) {
			System.out.println("[zfuzz] working on example " + p.getId());
			PipelinedFnParser parser = train(p);
			checkGoodPerf(parser, p, 0.99d, 0.0d, true);
		}
	}
	
	public PipelinedFnParser serializeAndDeserialize(PipelinedFnParser parser) {
		try {
			File f = File.createTempFile("foo", "bar");
			ObjectOutputStream oos = new ObjectOutputStream(
					new GZIPOutputStream(new FileOutputStream(f)));
			oos.writeObject(parser);
			oos.close();
			ObjectInputStream ois = new ObjectInputStream(
					new GZIPInputStream(new FileInputStream(f)));
			PipelinedFnParser r = (PipelinedFnParser) ois.readObject();
			ois.close();
			return r;
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	public PipelinedFnParser train(FNParse e) {
		PipelinedFnParser parser = new PipelinedFnParser(new ParserParams());
		((FrameIdStage) parser.getFrameIdStage()).params.tuneOnTrainingData = true;
		List<FNParse> dummy = Arrays.asList(e);
		parser.computeAlphabet(dummy, 5d, 99_000_000);
		parser.train(dummy);
		return parser;
	}
	
	public void checkGoodPerf(PipelinedFnParser p, FNParse gold, double targetF1Thresh,
			double fullF1Thresh, boolean verbose) {
		List<Sentence> s = DataUtil.stripAnnotations(Arrays.asList(gold));
		if(verbose)
			System.out.println("gold = " + Describe.fnParse(gold));
		FNParse hyp = p.predict(s).get(0);
		double targetF1 = BasicEvaluation.targetMicroF1.evaluate(
				BasicEvaluation.zip(Arrays.asList(gold), Arrays.asList(hyp)));
		double fullF1 = BasicEvaluation.fullMicroF1.evaluate(
				BasicEvaluation.zip(Arrays.asList(gold), Arrays.asList(hyp)));
		if(verbose) {
			System.out.println("hyp  = " + Describe.fnParse(hyp));
			System.out.println("targetF1 = " + targetF1);
			System.out.println("fullF1   = " + fullF1);
			serializeWeights(p, new File("saved-models/testing"), "checkGoodPerf@" + gold.getId());
		}
		assertTrue(targetF1 >= targetF1Thresh);
		assertTrue(fullF1 >= fullF1Thresh);
	}
	
}
