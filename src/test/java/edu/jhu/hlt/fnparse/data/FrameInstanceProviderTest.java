package edu.jhu.hlt.fnparse.data;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;

public class FrameInstanceProviderTest {

	private static void testFIP(FrameInstanceProvider fip, boolean verbose) {
		
		System.out.println("testing " + fip.getName());

		long start = System.currentTimeMillis();
		List<FNParse> sents = fip.getParsedSentences();
		long time = System.currentTimeMillis() - start;
		
		checkOrder(sents, fip);

		int numFIs = 0;
		Set<FNParse> uniqSents = new HashSet<FNParse>();
		for(FNParse s : sents) {
			for(FrameInstance fi : s.getFrameInstances()) {
				numFIs++;
				if(verbose) {
					System.out.println(String.format("frame %s; Trigger_by %s; Sentence %s",
							fi.getFrame(), fi.getTarget(), fi.getSentence().toString()));
				}
				assertEquals(fi.getFrame().numRoles(), fi.numArguments());
				for(int i = 0; i < fi.getFrame().numRoles(); i++) {
					assertNotNull(fi.getArgument(i));
					if(verbose) {
						System.out.println(String.format("Role: %s, Argument: %s",
								fi.getFrame().getRole(i), Arrays.toString(fi.getArgumentTokens(i))));
					}
				}
			}
			assertTrue(uniqSents.add(s));
		}
		System.out.printf("loaded %d FrameInstances in %d sentences took %.2f seconds\n\n",
				numFIs, sents.size(), time/1000d);
	}

	public static void checkOrder(List<FNParse> sents, FrameInstanceProvider fip) {
		List<FNParse> gotTheSecondTime = fip.getParsedSentences();
		assertEquals(sents, gotTheSecondTime);
	}
	
	@Test
	public void defaultConfigTest() {
		boolean verbose = false;
		testFIP(FileFrameInstanceProvider.fn15trainFIP, verbose);
		testFIP(FileFrameInstanceProvider.fn15testFIP, verbose);
		testFIP(FileFrameInstanceProvider.fn15lexFIP, verbose);
	}


//	@Test
//	public void testLexicographicExamples() {
//		FrameInstanceProvider fip = null;
//	}
	
}
