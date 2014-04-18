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

}
