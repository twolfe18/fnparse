package edu.jhu.hlt.fnparse.util;

import java.util.List;

import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;

public class FrameInstanceProviderTests {

	public static void main(String[] args) {
		System.out.println("starting...");
		FrameInstanceProvider fip = new DefaultConfiguration().getFrameInstanceProvider();
		
		long start = System.currentTimeMillis();
		List<FrameInstance> fis = fip.getFrameInstances();
		long time = System.currentTimeMillis() - start;
		
		for(FrameInstance fi : fis) {
			String line = String.format("instance of %s: %s", fi.getFrame(), fi);
			System.out.println(line);
		}
		System.out.printf("loading %d FrameInstances took %.2f seconds\n", fis.size(), time/1000d);
	}
}
