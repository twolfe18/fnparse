package edu.jhu.hlt.fnparse.util;

import java.util.*;

import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public class FrameInstanceProviderTests {

	public static void main(String[] args) {
		System.out.println("starting...");
		FrameInstanceProvider fip = new DefaultConfiguration().getFrameInstanceProvider();
		
		long start = System.currentTimeMillis();
		List<FrameInstance> fis = fip.getFrameInstances();
		long time = System.currentTimeMillis() - start;
		
		Set<Sentence> sents = new HashSet<Sentence>();
		for(FrameInstance fi : fis) {
			String line = String.format("instance of %s: %s", fi.getFrame(), fi);
			System.out.println(line);
			sents.add(fi.getSentence());
		}
		System.out.printf("loading %d FrameInstances in %d sentences took %.2f seconds\n",
				fis.size(), sents.size(), time/1000d);
	}
}
