
package edu.jhu.hlt.fnparse.data;

import static org.junit.Assert.*;

import java.util.*;

import org.junit.Test;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;

public class FrameIndexTest {
		
	@Test
	public void basic() {
		
		long start = System.currentTimeMillis();
		FrameIndex frameIndex = FrameIndex.getInstance();
		List<Frame> allFrames = frameIndex.allFrames();
		assertEquals(allFrames.size(), FrameIndex.framesInFrameNet + 1);	// +1 for nullFrame
		long time = System.currentTimeMillis() - start;
		
		Set<Integer> ids = new HashSet<Integer>();
		int max = -1;
		for(Frame f : allFrames) {
			System.out.println(f);
			assertTrue(ids.add(f.getId()));
			if(f.getId() > max)
				max = f.getId();
		}
		System.out.printf("reading %d frames took %.2f sec\n", allFrames.size(), time/1000d);
		assertEquals(max, allFrames.size()-1);
	}
	
}
