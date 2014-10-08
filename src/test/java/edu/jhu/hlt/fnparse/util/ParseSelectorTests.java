package edu.jhu.hlt.fnparse.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;

public class ParseSelectorTests {
	
	@Test
	public void inspect() {
		List<FNParse> parses = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		
		Counts<Frame> fCounts = new Counts<>();
		for(FNParse p : parses)
			for(FrameInstance fi : p.getFrameInstances())
				fCounts.increment(fi.getFrame());
		
		ParseSelector ps = new ParseSelector(parses);
		assertTrue(ps.size() == parses.size());
		
		for(int i=1; i<=20; i++) {
			FNParse n = ps.next();
			double v = ps.getValueOf(n);
			assertEquals(parses.size()-i, ps.size());
			System.out.println(Describe.fnParse(n));
			for(FrameInstance fi : n.getFrameInstances())
				System.out.print("\t" + fi.getFrame().getName() + ": " + fCounts.getCount(fi.getFrame()));
			System.out.println("\n\tvalue: " + v + "\n");
		}
	}

}
