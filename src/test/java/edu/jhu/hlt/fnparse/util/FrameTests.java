
package edu.jhu.hlt.fnparse.util;

import java.util.*;
import edu.jhu.hlt.fnparse.data.UsefulConstants;
import edu.jhu.hlt.fnparse.data.DataUtil;

public class FrameTests {
	static Boolean testparseFrameIndexXML() throws Exception{
		String name[] = DataUtil.parseFrameIndexXML(UsefulConstants.frameIndexXMLPath, 1019);
		for(int i = 0 ; i < 1019; i++){
		    System.out.println(name[i]);
		};
		return Boolean.TRUE;
	}
		
	public static void main(String[] args) {
		
		long start = System.currentTimeMillis();
		FrameIndex frameIndex = new FrameIndex();
		List<Frame> allFrames = frameIndex.allFrames();
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
		// try{
		// 	assert 	testparseFrameIndexXML();
		// }
		// catch Exception(e){
		// 	throw new RuntimeException(e);
		// }

	}
	
}
