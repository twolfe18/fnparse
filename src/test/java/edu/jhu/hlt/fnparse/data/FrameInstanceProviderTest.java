package edu.jhu.hlt.fnparse.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.*;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public class FrameInstanceProviderTest {
	
	/**
	 * i checked by hand and i think some of these instances are being skipped.
	 * if there is some reason they should be skipped, then remove them from the file
	 * or change the values in this hash map (i want to ensure this has been looked at).
	 */
	public Map<String, String> firstWordOfFirstSentence;
	
	@Before
	public void populateFirstWord() {
		firstWordOfFirstSentence = new HashMap<String, String>();
		firstWordOfFirstSentence.put("semlink", "Pierre");
		firstWordOfFirstSentence.put("fn15.train", "The");
		firstWordOfFirstSentence.put("fn15.test", "On"); 
		firstWordOfFirstSentence.put("fn15.lex", "Major");
	}

	private static void testFIP(FileFrameInstanceProvider fip, boolean verbose) {		
		System.out.println("testing " + fip.getName());
		long start = System.currentTimeMillis();
		List<FNParse> sents = fip.getParsedSentences();
		long time = System.currentTimeMillis() - start;

		checkOrder(sents, fip);

		int parsesWithMoreThanOneFI = 0;
		int numFIs = 0;
		Set<Sentence> uniqSents = new HashSet<Sentence>();
		for(FNParse s : sents) {
			if(s.numFrameInstances() > 1)
				parsesWithMoreThanOneFI++;
			for(FrameInstance fi : s.getFrameInstances()) {
				assertTrue(!fi.onlyTargetLabeled());
				numFIs++;
				if(verbose) {
					System.out.println(String.format("frame %s; Trigger_by %s; Sentence %s",
							fi.getFrame(), fi.getTarget(), fi.getSentence().toString()));
				}
				assertEquals("sent=" + s.getId() + ", fi: " + fi.toString(), fi.getFrame().numRoles(), fi.numArguments());
				for(int i = 0; i < fi.getFrame().numRoles(); i++) {
					assertNotNull(fi.getArgument(i));
					if(verbose) {
						System.out.println(String.format("Role: %s, Argument: %s",
								fi.getFrame().getRole(i), Arrays.toString(fi.getArgumentTokens(i))));
					}
				}
			}
			checkSentence(s.getSentence());
			assertTrue(uniqSents.add(s.getSentence()));
		}
		// lsc[0] counts lemmas that are same as the word
		// lsc[1] counts lemmas that are different from word
		int[] lsc = {0,0};
		for(Sentence s : uniqSents){
			// Basically I want to check that that the lemmas really are lemmas
			// and get a stat of how many lemmas are the same as the actual word
			// and how many are not
			updateLemmatizedVsSameCounter(s, lsc);
		}
		System.out.printf("there are %d lemmas that are same and %d lemmas that are different\n", lsc[0], lsc[1]);
		System.out.println("there are " + parsesWithMoreThanOneFI + " parses with more than one FrameInstance in them.");
		System.out.printf("loaded %d FrameInstances in %d sentences took %.2f seconds\n\n",
				numFIs, sents.size(), time/1000d);
	}
	
	public static void updateLemmatizedVsSameCounter(Sentence s, int[] lsc){
		int lemmadifferentFromWord = 0;
		int lemmaSameasWord = 0;
		for(int i = 0; i < s.size(); i++){
			String lemma = s.getLemma(i);
			String word = s.getWord(i);
			if(lemma.equals(word)){
				lemmaSameasWord+=1;
			} else {
				lemmadifferentFromWord+=1;
			}
		}
		lsc[0]+=lemmaSameasWord;
		lsc[1]+=lemmadifferentFromWord;
	}
	
	public static void checkSentence(Sentence s) {
		assertTrue(s.getId() + " should be at least length 1", s.size() > 0);
		for(int i=0; i<s.size(); i++) {
			assertTrue("lenth 0 word in :" + s.toString(), s.getWord(i).length() > 0);
			assertTrue("lenth 0 POS in :" + s.toString(), s.getPos(i).length() > 0);
		}
	}

	public static void checkOrder(List<FNParse> sents, FrameInstanceProvider fip) {
		List<FNParse> gotTheSecondTime = fip.getParsedSentences();
		assertEquals(sents, gotTheSecondTime);
	}

	@Test
	public void defaultConfigTest() {
		boolean verbose = false;
		
		testFIP(FileFrameInstanceProvider.fn15trainFIP, verbose);
		assertEquals(firstWordOfFirstSentence.get("fn15.train"),
				FileFrameInstanceProvider.fn15trainFIP.getParsedSentences().get(0).getSentence().getWord(0));

		testFIP(FileFrameInstanceProvider.fn15testFIP, verbose);
		assertEquals(firstWordOfFirstSentence.get("fn15.test"),
				FileFrameInstanceProvider.fn15testFIP.getParsedSentences().get(0).getSentence().getWord(0));
		
		testFIP(FileFrameInstanceProvider.fn15lexFIP, verbose);
		assertEquals(firstWordOfFirstSentence.get("fn15.lex"),
				FileFrameInstanceProvider.fn15lexFIP.getParsedSentences().get(0).getSentence().getWord(0));
		
		// TODO: Fix semlink datafiles and uncomment these tests
		// testFIP(FileFrameInstanceProvider.semlinkFIP, verbose);
		//assertEquals(firstWordOfFirstSentence.get("semlink"),
		//		FileFrameInstanceProvider.semlinkFIP.getParsedSentences().get(0).getSentence().getWord(0));
		// assertTrue(FileFrameInstanceProvider.semlinkFIP.getTaggedSentences().size() > 0);
		
	}
}
