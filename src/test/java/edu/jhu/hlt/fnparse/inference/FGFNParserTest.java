package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.gm.model.Var;
import edu.jhu.hlt.fnparse.data.FNFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.FGFNParser.FGFNParserSentence;
import edu.jhu.hlt.fnparse.inference.heads.BraindeadHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.spans.SingleWordSpanExtractor;

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
		System.out.println("[setup] examplar sentence = " + sentence);
		parser = new FGFNParser();
		hf = new BraindeadHeadFinder();
		
		List<Frame> frames = FrameIndex.getInstance().allFrames();
		System.out.println("[setup] #frames=" + frames.size());
	}
	
	@Test
	public void checkFrameElemHyp() {
		FGFNParserSentence ps = parser.new FGFNParserSentence(sentence);
//		FrameElementHypothesisFactory fehf = parser.getFrameElementIdentifier();
		assertTrue(ps.frameVars.size() > 0);
		for(int i=0; i<ps.frameVars.size(); i++) {
			FrameHypothesis f_i = ps.frameVars.get(i);
			Span target = f_i.getTargetSpan();
			System.out.printf("f_%d span=%s spanHead=%s #possibleFrames=%d\n",
					i, target, sentence.getLU(hf.head(target, sentence)), f_i.numPossibleFrames());
			assertTrue(f_i.numPossibleFrames() > 0);
			assertTrue(f_i.numPossibleFrames() > 1);	// if there is 1 or fewer, just set it to that?
			for(int f=0; f<f_i.numPossibleFrames(); f++)
				System.out.println("\t"+ f_i.getPossibleFrame(f));
			System.out.println();
			
//			for(int j=0; j<f_i.maxRoles(); j++) {
//				FrameElementHypothesis feh = fehf.make(f_i, j, sentence);
//				System.out.println(feh);
//			}
		}
	}
	
	@Test
	public void countVariables() {
		FGFNParserSentence ps = parser.new FGFNParserSentence(sentence);
		System.out.println("[countVariables] #frameVars=" + ps.frameVars.size());
		assertTrue(sentence.size() >= ps.frameVars.size() || !(parser.getTargetIdentifier() instanceof SingleWordSpanExtractor));
		System.out.println("[countVariables] i = " + sentence.size());
		System.out.println("[countVariables] ps.goldConf.size = " + ps.goldConf.size());
		int n = ps.frameVars.size();
		for(int i=0; i<ps.frameVars.size(); i++)
			n += ps.frameElemVars.get(i).size();
		System.out.println("[countVariables] n = " + n);
		assertEquals(n, ps.goldConf.size());
		assertTrue(n > 0);
	}
	
	@Test
	public void checkGoldConf() {
		FGFNParserSentence ps = parser.new FGFNParserSentence(sentence);
		System.out.println("[checkGoldConf] ps.goldConf.size=" + ps.goldConf.size());
		for(Var v : ps.getAllVars()) {
			assertTrue(ps.goldConf.getState(v) >= 0);
			assertTrue(ps.goldConf.getStateName(v) != null);
		}
	}
}
