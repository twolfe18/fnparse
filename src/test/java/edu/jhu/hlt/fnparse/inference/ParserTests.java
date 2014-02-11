package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.indexing.BasicBob;
import edu.jhu.hlt.fnparse.features.indexing.SuperBob;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser;
import edu.jhu.hlt.fnparse.util.Describe;

public class ParserTests {

	private FNParse dummyParse;
	
	@Before
	public void setupBob() {
		System.setProperty(SuperBob.WHICH_BOB, "BasicBob");
		System.setProperty(BasicBob.BASIC_BOBS_FILE, "feature-widths.txt");
		SuperBob.getBob(null).startup();
	}
	
	@After
	public void shutdownBob() {
		SuperBob.getBob(null).shutdown();
	}
	
	@Before
	public void setupDummyParse() {
		
		// NOTE: I'm using "jump" instead of "jumped" because "jumped.V" is not an LU for Self_motion
		String[] tokens  = new String[] {"The", "quick", "fox", "jump", "over", "the", "fence"};
		String[] pos     = new String[] {"DT",  "JJ",    "NN",  "VBD",    "IN",   "DT",  "NN"};
		int[] gov        = new int[]    {2,     2,       3,     -1,       3,      6,     4};
		String[] depType = new String[] {"G",   "G",     "G",   "G",      "G",    "G",   "G"};
		Sentence s = new Sentence("test", "sent1", tokens, pos, gov, depType);

		FrameIndex frameIdx = FrameIndex.getInstance();
		List<FrameInstance> instances = new ArrayList<FrameInstance>();
		
		Frame speed = frameIdx.getFrameByName("Speed");
		//System.out.println("speedArgs: " + Arrays.toString(speed.getRoles()));
		Span[] speedArgs = new Span[speed.numRoles()];
		Arrays.fill(speedArgs, Span.nullSpan);
		speedArgs[0] = Span.widthOne(1);	// Entity
		FrameInstance speedInst = FrameInstance.newFrameInstance(speed, Span.widthOne(1), speedArgs, s);
		instances.add(speedInst);
		
		Frame jump = frameIdx.getFrameByName("Self_motion");
		//System.out.println("speedArgs: " + Arrays.toString(jump.getRoles()));
		Span[] jumpArgs = new Span[jump.numRoles()];
		Arrays.fill(jumpArgs, Span.nullSpan);
		jumpArgs[0] = Span.widthOne(2);		// Self_mover
		jumpArgs[2] = Span.getSpan(4, 7);	// Path
		FrameInstance jumpInst = FrameInstance.newFrameInstance(jump, Span.widthOne(3), jumpArgs, s);
		instances.add(jumpInst);
		
		dummyParse = new FNParse(s, instances, true);
		System.out.println("[ParserTests] dummy parse:");
		System.out.println(Describe.fnParse(dummyParse));
	}
	
	@Test
	public void basic() {
		// should be able to overfit the data
		// give a simple sentence and make sure that we can predict it correctly when we train on it
		Parser parser = new Parser();
		List<FNParse> train = new ArrayList<FNParse>();
		List<Sentence> test = new ArrayList<Sentence>();
		train.add(dummyParse);
		test.add(dummyParse.getSentence());
		parser.train(train);
		//List<FNParse> predicted = parser.parse(test);
		//assertEquals(test.size(), predicted.size());
		//assertEquals(test, predicted);	// TODO implement equals
	}
	
}
