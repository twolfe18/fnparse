package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

import org.junit.*;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.evaluation.*;
import edu.jhu.hlt.fnparse.inference.Parser.Mode;
import edu.jhu.hlt.fnparse.util.Describe;

public class ParserTests {

	private FNParse dummyParse;

	@Before
	public void setupDummyParse() {
		dummyParse = makeDummyParse();
		System.out.println("[ParserTests] dummy parse:");
		System.out.println(Describe.fnParse(dummyParse));
	}
	
	public static FNParse makeDummyParse() {
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
		System.out.println("[setupDummyParse] adding instance of " + speed);
		
		Frame jump = frameIdx.getFrame("Self_motion");
		//System.out.println("speedArgs: " + Arrays.toString(jump.getRoles()));
		Span[] jumpArgs = new Span[jump.numRoles()];
		Arrays.fill(jumpArgs, Span.nullSpan);
		jumpArgs[0] = Span.getSpan(0, 2);	// Self_mover
		jumpArgs[2] = Span.getSpan(4, 7);	// Path
		FrameInstance jumpInst = FrameInstance.newFrameInstance(jump, Span.widthOne(3), jumpArgs, s);
		instances.add(jumpInst);
		System.out.println("[setupDummyParse] adding instance of " + jump);
		
		return new FNParse(s, instances);
	}
	
	@Test
	public void frameId() {
		Parser p = new Parser(Mode.FRAME_ID, false, true);
		overfitting(p, true, true, "FRAME_ID");
	}

	@Test
	public void frameIdWithLatentDeps() {
		boolean useLatentDeps = true;
		Parser p = new Parser(Mode.FRAME_ID, useLatentDeps, true);
		overfitting(p, true, true, "FRAME_ID_LATENT");
	}

	//@Test
	public void joint() {
		Parser p = new Parser(Mode.JOINT_FRAME_ARG, false, true);
		p.params.argDecoder.setRecallBias(1d);
		overfitting(p, false, true, "JOINT");
	}

	@Test
	public void pipeline() {
		Parser p = new Parser(Mode.PIPELINE_FRAME_ARG, false, true);
		p.params.argDecoder.setRecallBias(1d);
		overfitting(p, false, true, "PIPELINE");
	}
	
	
	
	
	
	private Parser trained, readIn;
	private File f;
	
	@Before
	public void setupSerStuff() throws IOException {
		f = File.createTempFile("ParserTests", ".model");
	}
	
	@Test
	public void serializationFrameId() throws IOException {
	
		// frame id
		trained = new Parser(Mode.FRAME_ID, false, true);
		overfitting(trained, true, true, "FRAME_ID_SER1");
		trained.writeModel(f);
		readIn = new Parser(Mode.JOINT_FRAME_ARG, false, true);	// not even same mode to try to screw things up!
		readIn.readModel(f);
		//readIn.params.targetPruningData = trained.params.targetPruningData;	// lets cheat a bit to speed things up...
		overfitting(readIn, true, false, "FRAME_ID_SER2");
	}

	@Test
	public void serializationFrameIdWithLatentDeps() throws IOException {
		trained = new Parser(Mode.FRAME_ID, true, true);
		overfitting(trained, true, true, "FRAME_ID_LATENT_SER1");
		trained.writeModel(f);
		readIn = new Parser(Mode.FRAME_ID, true, true);
		readIn.readModel(f);
		overfitting(readIn, true, false, "FRAME_ID_LATENT_SER2");
	}
	
	//@Test
	public void serializationJoint() throws IOException {
		trained = new Parser(Mode.JOINT_FRAME_ARG, false, true);
		trained.params.argDecoder.setRecallBias(1d);
		overfitting(trained, false, true, "JOINT_SER1");
		trained.writeModel(f);
		readIn = new Parser(Mode.FRAME_ID, false, true);	// not even same mode to try to screw things up!
		readIn.readModel(f);
		overfitting(readIn, false, false, "JOINT_SER2");
	}
	
	@Test
	public void serializationPipeline() throws IOException {
		trained = new Parser(Mode.PIPELINE_FRAME_ARG, false, true);
		trained.params.argDecoder.setRecallBias(1d);
		overfitting(trained, false, true, "PIPELINE_SER1");
		trained.writeModel(f);
		readIn = new Parser(Mode.FRAME_ID, false, true);	// not even same mode to try to screw things up!
		readIn.readModel(f);
		overfitting(readIn, false, false, "PIPELINE_SER2");
	}
	
	public void overfitting(Parser p, boolean onlyFrames, boolean doTraining, String desc) {
		// should be able to overfit the data
		// give a simple sentence and make sure that we can predict it correctly when we train on it
		List<FNParse> train = new ArrayList<FNParse>();
		List<Sentence> test = new ArrayList<Sentence>();
		train.add(dummyParse);
		test.add(dummyParse.getSentence());

		if(doTraining) {
			System.out.println("====== Training " + desc + " ======");
			if(p.params.debug) 
				p.train(train, 15, 1, 0.5d, 10d);
			else
				p.train(train, 15, 1, 0.5d, 10d);
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
	
	public void assertSameParse(FNParse a, FNParse b) {
		assertEquals(a.getSentence(), b.getSentence());
		assertEquals(a.getFrameInstances(), b.getFrameInstances());
	}
	
}
