package edu.jhu.hlt.fnparse.inference;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
 
import static org.junit.Assert.assertTrue;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Before;
import org.junit.Test;

import edu.jhu.gm.train.CrfObjective;
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
		Logger.getLogger(CrfObjective.class).setLevel(Level.INFO);
	}
	
	@Test
	public void basic() {
		PipelinedFnParser parser = canOverfitAnExample(makeDummyParse(), true, 1d, 1d);
		ModelIO.writeHumanReadable(parser.getFrameIdParams(), parser.getAlphabet(), new File("saved-models/testing/weights.frameId.txt"));
		ModelIO.writeHumanReadable(parser.getArgIdParams(), parser.getAlphabet(), new File("saved-models/testing/weights.argId.txt"));
		ModelIO.writeHumanReadable(parser.getArgSpanParams(), parser.getAlphabet(), new File("saved-models/testing/weights.argSpan.txt"));
	}
	
	@Test
	public void zfuzz() {
		List<FNParse> parses = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		parses = DataUtil.reservoirSample(parses, 30, new Random(9001));
		for(FNParse p : parses) {
			if(p.getFrameInstances().size() == 0)
				continue;
			canOverfitAnExample(p, true, 0.6d, 0.6d);
		}
	}
	
	public PipelinedFnParser canOverfitAnExample(FNParse p, boolean verbose, double targetF1Thresh, double fullF1Thresh) {
		PipelinedFnParser parser = new PipelinedFnParser();
		List<FNParse> dummy = Arrays.asList(p);	//makeDummyParse());
		parser.computeAlphabet(dummy);
		parser.train(dummy);
		List<FNParse> predicted = parser.predict(DataUtil.stripAnnotations(dummy));

		double targetF1 = BasicEvaluation.targetMicroF1.evaluate(BasicEvaluation.zip(dummy, predicted));
		double fullF1 = BasicEvaluation.fullMicroF1.evaluate(BasicEvaluation.zip(dummy, predicted));
		if(verbose) {
			System.out.println("gold = " + Describe.fnParse(dummy.get(0)));
			System.out.println("hyp  = " + Describe.fnParse(predicted.get(0)));
			System.out.println("targetF1 = " + targetF1);
			System.out.println("fullF1   = " + fullF1);
		}
		
		assertTrue(targetF1 >= targetF1Thresh);
		assertTrue(fullF1 >= fullF1Thresh);
		return parser;
	}

	
}
