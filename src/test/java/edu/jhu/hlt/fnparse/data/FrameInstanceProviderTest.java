package edu.jhu.hlt.fnparse.data;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public class FrameInstanceProviderTest {

	private static void testFIP(FrameInstanceProvider fip){

		long start = System.currentTimeMillis();
		List<FNParse> sents = fip.getParsedSentences();
		long time = System.currentTimeMillis() - start;
		
		checkOrder(sents, fip);

		int numFIs = 0;
		Set<FNParse> uniqSents = new HashSet<FNParse>();
		for(FNParse s : sents) {
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

	public static void checkOrder(List<FNParse> sents, FrameInstanceProvider fip) {
		List<FNParse> gotTheSecondTime = fip.getParsedSentences();
		assertEquals(sents, gotTheSecondTime);
	}
	
	@Test
	public void defaultConfigTest() {
		//System.out.println("testing default config...");
		testFIP(new FNFrameInstanceProvider());
	}

	/*@Test
	public void semlinkConfigTest() {
		//System.out.println("testing Semlink config...");
		testFIP(new SemLinkFrameInstanceProvider());
	}*/
}
