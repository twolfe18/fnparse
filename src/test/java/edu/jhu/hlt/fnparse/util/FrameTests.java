
package edu.jhu.hlt.fnparse.util;

import java.util.*;

public class FrameTests {

	public static void main(String[] args) {
		long start = System.currentTimeMillis();
		List<Frame> allFrames = Frame.allFrames();
		long time = System.currentTimeMillis() - start;
		Set<Integer> ids = new HashSet<Integer>();
		for(Frame f : allFrames) {
			System.out.println(f);
			if(!ids.add(f.getId()))
				throw new RuntimeException("f=" + f);
		}
		System.out.printf("reading %d frames took %.2f sec\n", allFrames.size(), time/1000d);
	}
	
}
