package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.FNFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.FGFNParser.FGFNParserSentence;
import edu.jhu.hlt.fnparse.inference.heads.BraindeadHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;

public class FGFNParserTest {

	private List<Sentence> examples;
	private Sentence sentence;
	private FGFNParser parser;
	private HeadFinder hf;
	
	@Before
	public void setup() {
		FrameInstanceProvider fip = new FNFrameInstanceProvider();
		examples = fip.getFrameInstances();
		sentence = examples.get(0);
		parser = new FGFNParser();
		hf = new BraindeadHeadFinder();
	}
	
	@Test
	public void checkFrameElemHyp() {
		FGFNParserSentence ps = parser.new FGFNParserSentence(sentence);
		FrameElementHypothesisFactory fehf = parser.getFrameElementIdentifier();
		for(int i=0; i<ps.frameVars.length; i++) {
			FrameHypothesis f_i = ps.frameVars[i];
			Span target = f_i.getTargetSpan();
			System.out.printf("f_%d span=%s spanHead=%s #possibleFrames=%d\n",
					i, target, sentence.getLU(hf.head(target, sentence)), f_i.numPossibleFrames());
			assertTrue(f_i.numPossibleFrames() > 0);
			assertTrue(f_i.numPossibleFrames() > 1);	// if there is 1 or fewer, just set it to that?
			for(int j=0; j<f_i.maxRoles(); j++) {
				FrameElementHypothesis feh = fehf.make(f_i, j, sentence);
				System.out.println(feh);
			}
		}
	}
	
	@Test
	public void countVariables() {
		FGFNParserSentence ps = parser.new FGFNParserSentence(sentence);
		assertEquals(sentence.size(), ps.frameVars.length);
		System.out.println("i = " + sentence.size());
		System.out.println(ps.goldConf.size());
		int n = 0;
		for(int i=0; i<sentence.size(); i++)
			n += ps.frameElemVars[i].length;
		System.out.println("n = " + n);
		assertEquals(n, ps.goldConf.size());
		assertTrue(n > 0);
	}
	
}
