package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.model.Var;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.heads.BraindeadHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.spans.SingleWordSpanExtractor;
import edu.jhu.hlt.fnparse.inference.variables.FrameHypothesis;

public class FGFNParserTest {

	private List<FNParse> examples;
	private FNParse exampleParse;
	private FGFNParser parser;
	private HeadFinder hf;
	
	@Before
	public void setup() {
		FrameInstanceProvider fip = new FNFrameInstanceProvider();
		examples = fip.getParsedSentences();
		exampleParse = examples.get(0);
		System.out.println("[setup] examplar sentence = " + exampleParse);
		parser = new FGFNParser();
		hf = new BraindeadHeadFinder();
		
		List<Frame> frames = FrameIndex.getInstance().allFrames();
		System.out.println("[setup] #frames=" + frames.size());
	}
	
	@Test
	public void checkPiecewiseTrain() {
		parser.train(examples, false);
	}
	
	@Test
	public void checkPiecewiseDecode() {
		List<FNParse> toParse = DataUtil.reservoirSample(examples, 2);
		List<FNParse> parsed = parser.parse(FGFNParser.stipLabels(toParse), false);
		System.out.println(parsed);
	}
	
	// likelihood may actually not be computable...
//	@Test
//	public void checkLogLikelihood() {
//		boolean startWithZeroedParams = true;
//		int k = 2;
//		while(k < this.examples.size()) {
//			List<Sentence> examples = DataUtil.reservoirSample(this.examples, k);
//			double ll = parser.getLogLikelihood(examples, startWithZeroedParams);
//			assertTrue(ll < 0d);
//			k *= 2;
//		}
//	}
	
	@Test
	public void checkFrameElemHyp() {
		FGFNParsing.JointParsing ps = new FGFNParsing.JointParsing(parser.params);
		
		// this actually populates the variables in ps
		ps.getTrainingInstance(exampleParse);
		
		assertTrue(ps.frameVars.size() > 0);
		for(int i=0; i<ps.frameVars.size(); i++) {
			FrameHypothesis f_i = ps.frameVars.get(i);
			Span target = f_i.getTargetSpan();
			System.out.printf("f_%d span=%s spanHead=%s #possibleFrames=%d\n",
					i, target, exampleParse.getSentence().getLU(hf.head(target, exampleParse.getSentence())), f_i.numPossibleFrames());
			assertTrue(f_i.numPossibleFrames() > 0);
			assertTrue(f_i.numPossibleFrames() > 1);	// if there is 1 or fewer, just set it to that?
			for(int f=0; f<f_i.numPossibleFrames(); f++)
				System.out.println("\t"+ f_i.getPossibleFrame(f));
			System.out.println();
		}
	}
	
	@Test
	public void countVariables() {
		FGFNParsing.JointParsing ps = new FGFNParsing.JointParsing(parser.params);
		
		// this actually populates the variables in ps
		ps.getTrainingInstance(exampleParse);
		
		System.out.println("[countVariables] #frameVars=" + ps.frameVars.size());
		assertTrue(exampleParse.getSentence().size() >= ps.frameVars.size() || !(parser.getTargetIdentifier() instanceof SingleWordSpanExtractor));
		System.out.println("[countVariables] i = " + exampleParse.getSentence().size());
		System.out.println("[countVariables] ps.goldConf.size = " + ps.goldConf.size());
		int n = ps.frameVars.size();
		for(int i=0; i<ps.frameVars.size(); i++)
			n += ps.roleVars.get(i).size();
		System.out.println("[countVariables] n = " + n);
		assertEquals(n, ps.goldConf.size());
		assertTrue(n > 0);
	}
	
	@Test
	public void checkGoldConf() {
		FGFNParsing.JointParsing ps = new FGFNParsing.JointParsing(parser.params);
		
		// this actually populates the variables in ps
		FgExample fge = ps.getTrainingInstance(exampleParse);
		
		System.out.println("[checkGoldConf] ps.goldConf.size=" + ps.goldConf.size());
		for(Var v : ps.getAllVariables()) {
			assertTrue(ps.goldConf.getState(v) >= 0);
			assertTrue(ps.goldConf.getStateName(v) != null);
		}
	}
}
