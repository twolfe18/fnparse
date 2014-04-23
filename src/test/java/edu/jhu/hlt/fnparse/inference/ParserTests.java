package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.inference.Parser.Mode;
import edu.jhu.hlt.fnparse.util.Describe;

public class ParserTests {
	
	private static final boolean testLatentDeps = false;
	private static final boolean testJoint = false;

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
	
	@Test
	public void frameId() {
		Parser p = new Parser(Mode.FRAME_ID, false, true);
		p.params.frameDecoder.setRecallBias(2d);
		overfitting(p, true, true, "FRAME_ID");
	}

	@Test
	public void frameIdWithLatentDeps() {
		if(!testLatentDeps) assertTrue("not testing latent deps", false);
		boolean useLatentDeps = true;
		Parser p = new Parser(Mode.FRAME_ID, useLatentDeps, true);
		overfitting(p, true, true, "FRAME_ID_LATENT");
	}

	@Test
	public void joint() {
		if(!testJoint) assertTrue("not testing joint", false);
		Parser p = new Parser(Mode.JOINT_FRAME_ARG, false, true);
		p.params.argDecoder.setRecallBias(1d);
		overfitting(p, false, true, "JOINT");
	}

	
	@Test
	public void pipeline() {
		Parser p = getFrameIdTrainedOnDummy();
		p.setMode(Mode.PIPELINE_FRAME_ARG, false);
		p.params.argDecoder.setRecallBias(1d);
		overfitting(p, false, true, "PIPELINE");
	}
	
	@Test
	public void pipelineWithLatentDeps() {
		if(!testLatentDeps) assertTrue("not testing latent deps", false);
		Parser p = getFrameIdTrainedOnDummy();
		p.params.argDecoder.setRecallBias(1d);
		overfitting(p, false, true, "PIPELINE_LATENT");
	}

	// TODO joint with latent
	
	
	@Test
	public void testDummyFrameIdModel() {
		Parser p = getFrameIdTrainedOnDummy();
		assertTrue(p.params.debug);
		assertEquals(Mode.FRAME_ID, p.params.mode);
		assertTrue(p.params.featIdx.size() > 0);
		assertTrue(p.params.model.l2Norm() > 0d);
	}
	
	public static Parser getFrameIdTrainedOnDummy() {
		Parser p = new Parser(Mode.FRAME_ID, false, true);
		p.params.frameDecoder.setRecallBias(2d);
		p.train(Arrays.asList(makeDummyParse()));
		return p;
	}

	
	public static void overfitting(Parser p, boolean onlyFrames, boolean doTraining, String desc) {
		// should be able to overfit the data
		// give a simple sentence and make sure that we can predict it correctly when we train on it
		List<FNParse> train = new ArrayList<FNParse>();
		List<Sentence> test = new ArrayList<Sentence>();
		FNParse dummyParse = makeDummyParse();
		train.add(dummyParse);
		test.add(dummyParse.getSentence());

		if(doTraining) {
			System.out.println("====== Training " + desc + " ======");
			p.train(train, 15, 1, 0.5d, 100d);
			p.writeWeights(new File("weights." + desc + ".txt"));
		}

		System.out.println("====== Running Prediction " + desc + " ======");
		List<FNParse> predicted = p.parse(test);
		assertEquals(test.size(), predicted.size());
		System.out.println("gold: " + Describe.fnParse(train.get(0)));
		System.out.println("hyp:  " + Describe.fnParse(predicted.get(0)));
		
		SentenceEval sentEval = new SentenceEval(dummyParse, predicted.get(0));
		
		assertEquals(1d, BasicEvaluation.targetMicroF1.evaluate(sentEval), 1e-8);
		assertEquals(1d, BasicEvaluation.targetMacroF1.evaluate(sentEval), 1e-8);
		if(!onlyFrames) {
			assertSameParse(train.get(0), predicted.get(0));
			assertEquals(1d, BasicEvaluation.fullMicroF1.evaluate(sentEval), 1e-8);
			assertEquals(1d, BasicEvaluation.fullMacroF1.evaluate(sentEval), 1e-8);
		}
		System.out.println("done with " + desc + " >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
	}
	
	public static void assertSameParse(FNParse a, FNParse b) {
		assertEquals(a.getSentence(), b.getSentence());
		assertEquals(a.getFrameInstances(), b.getFrameInstances());
	}
	
}
