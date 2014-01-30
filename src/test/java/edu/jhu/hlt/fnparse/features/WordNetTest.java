package edu.jhu.hlt.fnparse.features;

import java.io.File;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.RAMDictionary;
import edu.mit.jwi.data.ILoadPolicy;
import edu.mit.jwi.item.*;

public class WordNetTest {

	private IRAMDictionary dict;
	
	@Before
	public void setup() throws IOException {
		long start = System.currentTimeMillis();
		File f = new File("src/main/resources/dict");
		dict = new RAMDictionary(f, ILoadPolicy.IMMEDIATE_LOAD);
		dict.open();
		long time = System.currentTimeMillis() - start;
		System.out.printf("loaded wordnet in %.1f seconds\n", time/1000d);
	}
	
	@After
	public void shutdown() {
		dict.close();
	}
	
	@Test
	public void printSomeStuff() {
		show("write", POS.VERB);
		show("table", POS.NOUN);
		show("fast", POS.ADJECTIVE);
		show("quickly", POS.ADVERB);
	}
	
	public void show(String w, POS pos) {
		long start = System.currentTimeMillis();
		
		System.out.println("word = " + w + ", pos = " + pos);
		IIndexWord idxWord = dict.getIndexWord(w, pos);
		int numSenses = idxWord.getTagSenseCount();
		System.out.println("#senses = " + numSenses);
		//dict.getSynset(synsetId);
		for(IWordID wid : idxWord.getWordIDs()) {
			IWord word = dict.getWord(wid);
			System.out.println(word + "\t" + word.getRelatedMap());
		}
		
		long time = System.currentTimeMillis() - start;
		System.out.printf("basic search in %.1f seconds\n", time/1000d);
		System.out.println();
	}
}
