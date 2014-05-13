package edu.jhu.hlt.fnparse.inference;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

import org.junit.Test;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;

public class TargetPruningDataTests {

	
	@Test
	public void checkForAFewLUs() {
		Map<LexicalUnit, List<Frame>> framesByLU = TargetPruningData.getInstance().getLU2Frames();
		FrameIndex fi = FrameIndex.getInstance();
		checkLU(new LexicalUnit("area", "N"), framesByLU, fi.getFrame("Dimension"));
		checkLU(new LexicalUnit("year", "N"), framesByLU, fi.getFrame("Calendric_unit"));
	}
	
	public void checkLU(LexicalUnit shouldBeThere, Map<LexicalUnit, List<Frame>> where, Frame... shouldInclude) {
		
		for(Frame f : shouldInclude) {
			boolean found = false;
			for(int i=0; i<f.numLexicalUnits() && !found; i++)
				found |= shouldBeThere.equals(f.getLexicalUnit(i));
			assertTrue(found);
		}
		
		assertTrue(where.containsKey(shouldBeThere));
		
		List<Frame> fs = where.get(shouldBeThere);
		assertNotNull("should contain " + shouldBeThere.getFullString(), fs);
		assertTrue(fs.size() >= 1);
		for(Frame f : shouldInclude)
			assertTrue(fs.contains(f));
	}
}
