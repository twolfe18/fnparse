package edu.jhu.hlt.fnparse.features;

import java.io.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.mit.jwi.*;
import edu.mit.jwi.item.*;

public class WordNetTest {

	private IRAMDictionary dict;
	
	@Before
	public void setup() throws IOException {
		dict = TargetPruningData.getInstance().getWordnetDict();
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
