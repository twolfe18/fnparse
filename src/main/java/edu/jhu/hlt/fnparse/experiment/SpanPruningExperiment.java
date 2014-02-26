package edu.jhu.hlt.fnparse.experiment;

import java.util.*;

import edu.jhu.hlt.fnparse.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;

/**
 * there seems to be a mismatch between how I'm trying to do pruning
 * (classify a headword as possibly evoking a frame or not), and how
 * semafor/LTH does it (by only including spans that were seen in training data).
 * 
 * I can do it their way, but it leads to weird sparsity patterns:
 * multiple f_i may give rise to a particular span seen in training data
 * (even though the expansion var for those f_i's might also be sparse).
 * 
 * For now I just want to verify that I can reproduce their target identification numbers:
 * Semafor:		F1=79.21	P=89.92		R=70.79
 * LTH:			F1=76.10	P=87.87		R=67.11
 * (reported on page 8 of http://www.ark.cs.cmu.edu/SEMAFOR/das+schneider+chen+smith.tr10.pdf)
 * 
 * @author travis
 */
public class SpanPruningExperiment {
	
//	static class TrieElem<T> {
//		public int size();
//		public T get(int i);
//	}
//	
//	static class Trie<N extends TrieElem<T>, T> {
//		
//	}
	
	// i want an efficient way to store all the (lemmatized) spans seen in the training data
	// lets count the suckers to see if we can fit them in a hashmap
	
	public static void generateSpans(Iterable<FNParse> in, Collection<List<String>> out) {
		int[] lengthHist = new int[10];
		int c = 0;
		for(FNParse p : in) {
			Sentence s = p.getSentence();
			for(FrameInstance fi : p.getFrameInstances()) {
				Span target = fi.getTarget();
				List<String> lemmas = new ArrayList<String>();
				for(int i=target.start; i<target.end; i++)
					lemmas.add(s.getWord(i));	// TODO lemmatization
				out.add(lemmas);
				lengthHist[target.width()]++;
			}
			if(c++ % 1000 == 0)
				System.out.println("[generateSpans] after " + c + " sentences, there are " + out.size() + " target spans");
		}
		System.out.println("histogram of lengths:");
		for(int i=0; i<lengthHist.length; i++)
			System.out.printf("%d \t %d\n", i, lengthHist[i]);
	}
	
	public static void main(String[] args) {
		
		// split up the train data
		System.out.println("[" + SpanPruningExperiment.class.getName() + "] getting the data...");
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> dev = new ArrayList<FNParse>();
		DataSplitter ds = new DataSplitter();
		ds.split(FileFrameInstanceProvider.fn15trainFIP.getParsedSentences(), train, dev, 0.25d, "span-pruning-exp");
		
		// use some train data and all the lex data for extracting target spans
		List<FNParse> extractTargetsFrom = new ArrayList<FNParse>();
		extractTargetsFrom.addAll(train);
		extractTargetsFrom.addAll(FileFrameInstanceProvider.fn15lexFIP.getParsedSentences());
		
		System.out.println("[" + SpanPruningExperiment.class.getName() + "] extracting spans...");
		Set<List<String>> allSpans = new HashSet<List<String>>();
		generateSpans(extractTargetsFrom, allSpans);
		System.out.println("#spans=" + allSpans.size());
		
	}
}
