
package edu.jhu.hlt.fnparse.util;

import java.util.*;

public class FrameTests {

	public static void main(String[] args) {
		long start = System.currentTimeMillis();
		List<Frame> allFrames = Frame.allFrames();
		long time = System.currentTimeMillis() - start;
		Set<Integer> ids = new HashSet<Integer>();
		int max = -1;
		for(Frame f : allFrames) {
			System.out.println(f);
			if(!ids.add(f.getId()))
				throw new RuntimeException("f=" + f);
			if(f.getId() > max)
				max = f.getId();
		}
		System.out.printf("reading %d frames took %.2f sec\n", allFrames.size(), time/1000d);
		if(max != allFrames.size()-1)
			throw new RuntimeException("max=" + max + ", allFrames.size=" + allFrames.size());
	}
	
}
