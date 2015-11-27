package edu.jhu.hlt.fnparse.experiment;

import java.util.*;

import edu.jhu.hlt.fnparse.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.util.DataSplitter;
import edu.jhu.hlt.tutils.Span;

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
 * Semafor: F1=79.21  P=89.92   R=70.79
 * LTH:     F1=76.10  P=87.87   R=67.11
 * (reported on page 8 of http://www.ark.cs.cmu.edu/SEMAFOR/das+schneider+chen+smith.tr10.pdf)
 * 
 * NOTE: I'm an idiot and started deleting words from the LTH list without looking at the above
 * details. You can no longer rely on this being the list that they used, check the paper directly.
 * 
 * @author travis
 */
public class SpanPruningExperiment {
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
		// Split up the train data
		System.out.println("[" + SpanPruningExperiment.class.getName() + "] getting the data...");
		List<FNParse> all = DataUtil.iter2list(FileFrameInstanceProvider.fn15trainFIP.getParsedSentences());
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> dev = new ArrayList<FNParse>();
		DataSplitter ds = new DataSplitter();
		ds.split(all, train, dev, 0.25d, "span-pruning-exp");

		// Use some train data and all the lex data for extracting target spans
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

	// Determiners should be a huge pruning speedup (they're common)
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
		//lthSpecialWords.addAll(Arrays.asList("above", "against", "at", "below", "beside", "in", "on", "over", "under"));
		lthSpecialWords.addAll(Arrays.asList("course", "particular"));
		//lthSpecialWords.addAll(Arrays.asList("after", "before"));
		lthSpecialWords.addAll(Arrays.asList("into", "through"));
		lthSpecialWords.addAll(Arrays.asList("as", "so", "for", "with"));
		//lthSpecialWords.add("of");
		//lthSpecialWords.add("to");
		lthSpecialWords.add("will");
		lthSpecialWords.addAll(Arrays.asList("is", "be", "are"));	// all have 0 frames in training
	}
	public static List<String> alsoOfIterest = Arrays.asList(
			"was", "got", "kept", "ought", "has", "had", "can", "may", "must", "not");

	// Surprisingly, Locative_relation is a significant frame:
	// grep -c Locative_relation toydata/fn15-fulltext.frames
	// 1235
	// Which can be compared to the number of times "at" or "near" appear
	// awk -F"\t" '$5 == "at" || $5 == "near"' <toydata/fn15-fulltext.conll | wc -l
	// 380
	// grep Locative_relation toydata/fn15-frameindexLU | awk -F"\t" '{print $NF}' | tr '\n' ' '
	// above-ground.a above.prep abut.v abutting.a adjacent.a adjoin.v adjoining.a against.prep "all over.prep" along.prep amid.prep among.prep around.prep astride.prep at.prep athwart.prep atop.prep below.prep beneath.prep beside.prep between.prep beyond.prep border.v bracket.v by.prep contact.v distant.a down.prep east.prep elsewhere.adv everywhere.adv here.adv in.prep inland.a inside.prep mainland.n meet.v near.prep neighbor.v neighboring.a "next to.prep" north.prep northeast.prep off.prep offshore.a on.prep on_top_of.prep opposite.prep out.prep outlying.a outside.prep over.prep past.prep south.prep southeast.prep surrounding.a there.adv throughout.prep to.prep touch.v ubiquitous.a under.prep underground.a underground.adv underneath.prep up.prep upon.prep west.prep where.adv

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
