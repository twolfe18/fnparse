package edu.jhu.hlt.fnparse.data;

import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.util.DefaultConfiguration;
import edu.jhu.hlt.fnparse.util.SemlinkConfiguration;

public class FrameInstanceProviderTest {

	private static void testFIP(FrameInstanceProvider fip){

		long start = System.currentTimeMillis();
		List<Sentence> sents = fip.getFrameInstances();
		long time = System.currentTimeMillis() - start;

		int numFIs = 0;
		Set<Sentence> uniqSents = new HashSet<Sentence>();
		for(Sentence s : sents) {
			for(FrameInstance fi : s.getFrameInstances()) {
				numFIs++;
				String line = String.format("frame %s; Trigger_by %s; Sentence %s", fi.getFrame(), fi.getTarget(), Arrays.toString(fi.getSentence().getWord()));
				System.out.println(line);
				for(int i = 0; i < fi.getFrame().numRoles(); i++){
					if(fi.getArgument(i) != null){
						System.out.println(String.format("Role: %s, Argument: %s", fi.getFrame().getRole(i), Arrays.toString(fi.getArgumentTokens(i))));
					}
				}
			}
			assertTrue(uniqSents.add(s));
		}
		System.out.printf("loading %d FrameInstances in %d sentences took %.2f seconds\n",
				numFIs, sents.size(), time/1000d);
	}

	@Test
	public void defaultConfigTest() {
		//System.out.println("testing default config...");
		testFIP(new DefaultConfiguration().getFrameInstanceProvider());
	}

	@Test
	public void semlinkConfigTest() {
		//System.out.println("testing Semlink config...");
		testFIP(new SemlinkConfiguration().getFrameInstanceProvider());
	}
}
