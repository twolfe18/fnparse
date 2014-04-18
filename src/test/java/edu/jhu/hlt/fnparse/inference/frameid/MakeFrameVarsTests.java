package edu.jhu.hlt.fnparse.inference.frameid;

import static org.junit.Assert.*;

import org.junit.Test;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.ParserTests;
import edu.jhu.hlt.fnparse.inference.Parser.Mode;

// TODO make these pass!
public class MakeFrameVarsTests {
	
//	WARNING: frame filtering heuristic didn't extract <Frame 151 Cardinal_numbers has 4 roles> for <LU thousand.CD>
//	WARNING: frame filtering heuristic didn't extract <Frame 142 Building has 16 roles> for <LU building.NN>
	
//	WARNING: frame filtering heuristic didn't extract <Frame 754 Quantity has 5 roles> for <LU lot.NN>


	@Test
	public void building() {
		search("building", "NN", FrameIndex.getInstance().getFrame("Building"));
	}

	@Test
	public void numbers() {
		search("thousands", "CD", FrameIndex.getInstance().getFrame("Cardinal_numbers"));
	}

	public void search(String word, String pos, Frame lookingFor) {
		Parser parser = new Parser(Mode.FRAME_ID, false, true);
		FNParse p = ParserTests.makeDummyParse();
		Sentence s = p.getSentence();
		s.getWords()[4] = word;
		s.getPos()[4] = pos;
		boolean found = false;
		FrameIdSentence fid = new FrameIdSentence(s, parser.params);
		for(FrameVars fv : fid.getPossibleFrames()) {
			if(fv.getTargetHeadIdx() != 4) continue;
			for(int t=0; t<fv.numFrames(); t++)
				found |= fv.getFrame(t) == lookingFor;
		}
		System.out.println("looking for " + lookingFor.getName() + 
				" triggered by " + new LexicalUnit(word, pos).getFullString());
		//System.out.println("found " + found);
		assertTrue(found);
	}
	
	// OLD CODE THAT MIGHT BE USEFUL
	/*
	public static void pruningDebug(LexicalUnit head, Frame missed) {
		
		Set<LexicalUnit> lexInstances;
		System.out.printf("[pruningDebug] did not recall the frame %s given the trigger %s\n",
				missed.getName(), head.getFullString());
		
		// all LUs for this frame
		for(int i=0; i<missed.numLexicalUnits(); i++)
			System.out.printf("[pruningDebug] %s.LU[%d]=%s\n", missed.getName(), i, missed.getLexicalUnit(i));
		
		// all examples lex for this frame
		lexInstances = observedTriggers(fnLex, missed);
		System.out.printf("[pruningDebug] triggers for %s in lex/p examples (%d):", missed.getName(), lexInstances.size());
		for(LexicalUnit lu : lexInstances)
			System.out.print(" " + lu.getFullString());
		System.out.println();
		
		lexInstances = observedTriggers(fnLex, missed);
		System.out.printf("[pruningDebug] triggers for %s in lex/t examples (%d):", missed.getName(), lexInstances.size());
		for(LexicalUnit lu : lexInstances)
			System.out.print(" " + lu.getFullString());
		System.out.println();
		
		lexInstances = observedTriggers(fnTrain, missed);
		System.out.printf("[pruningDebug] triggers for %s in train examples (%d):", missed.getName(), lexInstances.size());
		for(LexicalUnit lu : lexInstances)
			System.out.print(" " + lu.getFullString());
		System.out.println();
		
		System.out.println();
	}
	
	public static void mainDebugging(String[] args) {
		FrameIndex fi = FrameIndex.getInstance();
		
		// old
//		pruningDebug(new LexicalUnit("toxins", "NNS"), fi.getFrameByName("Toxic_substance"));
//		pruningDebug(new LexicalUnit("Potential", "JJ"), fi.getFrameByName("Capability"));
//		pruningDebug(new LexicalUnit("of", "IN"), fi.getFrameByName("Partitive"));
//		pruningDebug(new LexicalUnit("representatives", "NNS"), fi.getFrameByName("Leadership"));
		
		pruningDebug(new LexicalUnit("idea", "NN"), fi.getFrame("Desirable_event"));
		pruningDebug(new LexicalUnit("do", "VB"), fi.getFrame("Intentionally_act"));
		pruningDebug(new LexicalUnit("sooner", "RBR"), fi.getFrame("Time_vector"));
		pruningDebug(new LexicalUnit("several", "JJ"), fi.getFrame("Quantity"));
		pruningDebug(new LexicalUnit("factories", "NNS"), fi.getFrame("Locale_by_use"));
		
//[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidate set of frames for <LU idea.NN> did not include the gold frame: <Frame 306 Desirable_event>
//[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidate set of frames for <LU do.VB> did not include the gold frame: <Frame 525 Intentionally_act>
//[setGold] invoking USE_NULLFRAME_FOR_FILTERING_MISTAKES because the candidate set of frames for <LU sooner.RBR> did not include the gold frame: <Frame 954 Time_vector>
	}
	*/

}
