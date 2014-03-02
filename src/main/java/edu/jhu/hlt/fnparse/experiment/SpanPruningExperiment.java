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
		List<FNParse> all = DataUtil.iter2list(FileFrameInstanceProvider.fn15trainFIP.getParsedSentences());
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> dev = new ArrayList<FNParse>();
		DataSplitter ds = new DataSplitter();
		ds.split(all, train, dev, 0.25d, "span-pruning-exp");
		
		// use some train data and all the lex data for extracting target spans
		List<FNParse> extractTargetsFrom = new ArrayList<FNParse>();
		extractTargetsFrom.addAll(train);
		extractTargetsFrom.addAll(DataUtil.iter2list(FileFrameInstanceProvider.fn15lexFIP.getParsedSentences()));
		
		System.out.println("[" + SpanPruningExperiment.class.getName() + "] extracting spans...");
		Set<List<String>> allSpans = new HashSet<List<String>>();
		generateSpans(extractTargetsFrom, allSpans);
		System.out.println("#spans=" + allSpans.size());
		
		checkLthFilteringRules(train);
		checkDeterminerPruning(train);
	}
	
	// determiners should be a huge pruning speedup (they're common)
	public static void checkDeterminerPruning(List<FNParse> examples) {
		int totalTargets = 0;
		int widthOneTargets = 0;
		int detTargets = 0;
		for(FNParse p : examples) {
			Sentence s = p.getSentence();
			for(FrameInstance fi : p.getFrameInstances()) {
				totalTargets++;
				Span target = fi.getTarget();
				if(target.width() > 1) continue;
				widthOneTargets++;
				if(s.getLU(target.start).pos.endsWith("DT"))	// includes "PDT"
					detTargets++;
			}
		}
		System.out.printf("#width>1-targets=%d %.1f%%\n",
				totalTargets-widthOneTargets, (100d*(totalTargets-widthOneTargets))/totalTargets);
		System.out.printf("#determiner-targets=%d %.1f%%\n", detTargets, (100d*detTargets)/totalTargets);
	}
	
	public static List<String> lthSpecialWords;
	static {
		lthSpecialWords = new ArrayList<String>();
		lthSpecialWords.addAll(Arrays.asList("have", "be", "will"));
		lthSpecialWords.addAll(Arrays.asList("above", "against", "at", "below", "beside", "in", "on", "over", "under"));
		lthSpecialWords.addAll(Arrays.asList("course", "particular"));
		lthSpecialWords.addAll(Arrays.asList("after", "before"));
		lthSpecialWords.addAll(Arrays.asList("into", "to", "through"));
		lthSpecialWords.addAll(Arrays.asList("as", "so", "for", "with"));
		lthSpecialWords.add("of");
	}
	public static List<String> alsoOfIterest = Arrays.asList(
			"make", "give", "was", "got", "kept", "is", "will",
			"ought", "has", "had", "do", "can", "may", "must", "not");
	
	public static void checkLthFilteringRules(List<FNParse> examples) {
		
		System.out.println("[checkLthFilteringRules] LTH words   = " + lthSpecialWords);
		System.out.println("[checkLthFilteringRules] other words = " + alsoOfIterest);
		
		final Map<String, Integer> countInTarget = new HashMap<String, Integer>();
		final Map<String, Integer> countTotal = new HashMap<String, Integer>();
		int totalWords = 0;
		int totalTargets = 0;
		int widthOneTargets = 0;
		for(FNParse p : examples) {
			Sentence s = p.getSentence();
			for(FrameInstance fi : p.getFrameInstances()) {
				totalTargets++;
				Span target = fi.getTarget();
				for(int i=target.start; i<target.end; i++) {
					String w = s.getWord(i).toLowerCase();
					if(lthSpecialWords.contains(w) || alsoOfIterest.contains(w)) {
						Integer c = countInTarget.get(w);
						if(c == null) c = 0;
						c++;
						countInTarget.put(w, c+1);
					}
				}
				if(target.width() == 1) widthOneTargets++;
			}
			for(int i=0; i<s.size(); i++) {
				String w = s.getWord(i).toLowerCase();
				Integer c = countTotal.get(w);
				if(c == null) c = 0;
				countTotal.put(w, c+1);
				totalWords++;
			}
		}
		List<String> words = new ArrayList<String>(countInTarget.keySet());
		Collections.sort(words, new Comparator<String>() {
			@Override
			public int compare(String o1, String o2) {
				Integer c1 = countInTarget.get(o1);
				Integer c2 = countInTarget.get(o2);
				if(c1 == null) c1 = 0;
				if(c2 == null) c2 = 0;
				return c1 - c2;
			}
		});
		double cumulativeMissed = 0d;
		System.out.println("count\tprop\tcumulative\tprevalence\tword");
		for(String word : words) {
			Integer c = countInTarget.get(word);
			if(c == null) c = 0;
			double p = (100d*c)/totalTargets;
			cumulativeMissed += p;
			Integer cAll = countTotal.get(word);
			if(cAll == null) cAll = 0;
			double prevalence = Math.log((100d*(cAll+1)) / totalWords);
			System.out.printf("%d\t%.2f %%\t%.2f %%\t\t%.2f\t\t%s\n", c, p, cumulativeMissed, prevalence, word);
		}
		System.out.println(widthOneTargets + " of " + totalTargets + " targets were width one (" +
			((100d*widthOneTargets)/totalTargets) + "%)");
	}
	
}
