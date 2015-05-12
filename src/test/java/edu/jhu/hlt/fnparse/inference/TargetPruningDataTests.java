package edu.jhu.hlt.fnparse.inference;

import java.util.List;

import static org.junit.Assert.*;

import org.junit.Test;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;

public class TargetPruningDataTests {

	@Test
	public void checkForAFewLUs() {
		FrameIndex fi = FrameIndex.getFrameNet();
		checkLU(new LexicalUnit("area", "N"), fi.getFrame("Dimension"));
		checkLU(new LexicalUnit("year", "N"), fi.getFrame("Calendric_unit"));
	}

	public void checkLU(LexicalUnit shouldBeThere, Frame... shouldInclude) {
		TargetPruningData data = TargetPruningData.getInstance();

		for(Frame f : shouldInclude) {
			boolean found = false;
			for(int i=0; i<f.numLexicalUnits() && !found; i++)
				found |= shouldBeThere.equals(f.getLexicalUnit(i));
			assertTrue(found);
		}

		List<Frame> fs = data.getFramesFromLU(shouldBeThere);
		assertNotNull("should contain " + shouldBeThere.getFullString(), fs);
		assertTrue(fs.size() >= 1);
		for(Frame f : shouldInclude)
			assertTrue(fs.contains(f));
	}
}
