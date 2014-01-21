package edu.jhu.hlt.fnparse.util;

import java.util.*;

import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public class FrameInstanceProviderTests {

	private static void testFIP(FrameInstanceProvider fip){

		long start = System.currentTimeMillis();
		List<FrameInstance> fis = fip.getFrameInstances();
		long time = System.currentTimeMillis() - start;

		Set<Sentence> sents = new HashSet<Sentence>();
		for(FrameInstance fi : fis) {
			String line = String.format("frame %s; Trigger_by %s; Sentence %s", fi.getFrame(), fi.getTargetWord(), Arrays.toString(fi.getSentence().getWord()));
			System.out.println(line);
			for(int i = 0; i < fi.getFrame().numRoles(); i++){
				if(fi.getArgument(i) != null){
					System.out.println(String.format("Role: %s, Argument: %s", fi.getFrame().getRole(i), Arrays.toString(fi.getArgumentTokens(i))));
				}
			}
			sents.add(fi.getSentence());
		}
		System.out.printf("loading %d FrameInstances in %d sentences took %.2f seconds\n",
				fis.size(), sents.size(), time/1000d);
	}
	public static void main(String[] args) {
		System.out.println("starting...");
		System.out.println("testing default config...");
		testFIP(new DefaultConfiguration().getFrameInstanceProvider());
		System.out.println("testing Semlink config...");
		testFIP(new SemlinkConfiguration().getFrameInstanceProvider());
	}
}
