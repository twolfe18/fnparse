package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.*;

import java.io.File;
import java.util.*;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.*;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.evaluation.*;
import edu.jhu.hlt.fnparse.inference.newstuff.*;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.Mode;
import edu.jhu.hlt.fnparse.util.Describe;

public class ParserTests {

	private FNParse dummyParse;

	@Before
	public void setupDummyParse() {
		
		String[] tokens  = new String[] {"The", "fox", "quickly", "jumped", "over", "the", "fence"};
		String[] lemmas = new String[] {"The", "fox", "quickly", "jump", "over", "the", "fence"};
		String[] pos     = new String[] {"DT",  "NN",    "RB",  "VBD",    "IN",   "DT",  "NN"};
		int[] gov        = new int[]    {1,     3,       3,     -1,       3,      6,     4};
		String[] depType = new String[] {"G",   "G",     "G",   "G",      "G",    "G",   "G"};
		Sentence s = new Sentence("test", "sent1", tokens, pos, lemmas, gov, depType);

		FrameIndex frameIdx = FrameIndex.getInstance();
		List<FrameInstance> instances = new ArrayList<FrameInstance>();
		
		Frame speed = frameIdx.getFrameByName("Speed");
		//System.out.println("speedArgs: " + Arrays.toString(speed.getRoles()));
		Span[] speedArgs = new Span[speed.numRoles()];
		Arrays.fill(speedArgs, Span.nullSpan);
		speedArgs[0] = Span.getSpan(0, 2);	// Entity
		FrameInstance speedInst = FrameInstance.newFrameInstance(speed, Span.widthOne(2), speedArgs, s);
		instances.add(speedInst);
		System.out.println("[setupDummyParse] adding instance of " + speed);
		
		Frame jump = frameIdx.getFrameByName("Self_motion");
		//System.out.println("speedArgs: " + Arrays.toString(jump.getRoles()));
		Span[] jumpArgs = new Span[jump.numRoles()];
		Arrays.fill(jumpArgs, Span.nullSpan);
		jumpArgs[0] = Span.getSpan(0, 2);	// Self_mover
		jumpArgs[2] = Span.getSpan(4, 7);	// Path
		FrameInstance jumpInst = FrameInstance.newFrameInstance(jump, Span.widthOne(3), jumpArgs, s);
		instances.add(jumpInst);
		System.out.println("[setupDummyParse] adding instance of " + jump);
		
		dummyParse = new FNParse(s, instances);
		System.out.println("[ParserTests] dummy parse:");
		System.out.println(Describe.fnParse(dummyParse));
	}
	
	//@Test
	public void frameId() {
		Parser p = new Parser(Mode.FRAME_ID, true);
		overfitting(p, true, "FRAME_ID");
	}

	//@Test
	public void joint() {
		Parser p = new Parser(Mode.JOINT_FRAME_ARG, true);
		p.params.argDecoder.setRecallBias(1d);
		overfitting(p, false, "JOINT");
	}

	@Test
	public void pipeline() {
		Parser p = new Parser(Mode.PIPELINE_FRAME_ARG, true);
		p.params.argDecoder.setRecallBias(1d);
		overfitting(p, false, "PIPELINE");
	}
	
	public void overfitting(Parser p, boolean onlyFrames, String desc) {
		// should be able to overfit the data
		// give a simple sentence and make sure that we can predict it correctly when we train on it
		List<FNParse> train = new ArrayList<FNParse>();
		List<Sentence> test = new ArrayList<Sentence>();
		
		System.out.println("====== Training " + desc + " ======");
		train.add(dummyParse);
		test.add(dummyParse.getSentence());

		if(p.params.debug) {
			p.train(train, 4, 1, 0.5d, 1d);
			p.train(train, 4, 1, 0.5d, 1d);
		}
		else	// for full feature set, need less regularization and more iterations to converge
			p.train(train, 15, 1, 0.5d, 10d);
		p.writeoutWeights(new File("weights.txt"));
		
		System.out.println("====== Running Prediction " + desc + " ======");
		List<FNParse> predicted = p.parse(test);
		assertEquals(test.size(), predicted.size());
		System.out.println("gold: " + Describe.fnParse(train.get(0)));
		System.out.println("hyp:  " + Describe.fnParse(predicted.get(0)));
		
		SentenceEval sentEval = new SentenceEval(dummyParse, predicted.get(0));
		
		if(onlyFrames) {
			assertEquals(1d, BasicEvaluation.targetMicroF1.evaluate(sentEval), 1e-8);
			assertEquals(1d, BasicEvaluation.targetMacroF1.evaluate(sentEval), 1e-8);
		}
		else {
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
