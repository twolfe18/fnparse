package edu.jhu.hlt.fnparse.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.features.Features.*;
import edu.jhu.util.Alphabet;

public final class BasicFrameFeatures extends AbstractFeatures<BasicFrameFeatures> implements F {
	
	public BasicFrameFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}

	public final boolean verbose = false;
	public final boolean debug = true;
	
	public static class Timer {
		private String id;
		private int count;
		private long time;
		private long lastStart = -1;
		private int printIterval;
		
		public Timer(String id) {
			this.id = id;
			printIterval = -1;
		}
		public Timer(String id, int printInterval) {
			this.id = id;
			this.printIterval = printInterval;
		}
		
		public void start() {
			lastStart = System.currentTimeMillis();
		}
		
		public long stop() {
			long t = System.currentTimeMillis() - lastStart;
			time += t;
			count++;
			if(printIterval > 0 && count % printIterval == 0)
				System.out.printf("<Timer %s %.2f sec total, %.1f k call/sec\n", id, time/1000d, ((double)count)/time);
			return t;
		}
		
		public double totalTimeInSec() { return time / 1000d; }
		
		public static final class NoOp extends Timer {
			public NoOp(String id) { super(id); }
			public NoOp(String id, int printInterval)  { super(id, printInterval); }
			public void start() {}
			public long stop() { return -1; }
		}
		public static final Timer noOp = new NoOp("noOp");
	}

	public Timer full = Timer.noOp; //new Timer("all", 75000);
	public Timer parentTimer = Timer.noOp; //new Timer("parent", 75000);
	public Timer childTimer = Timer.noOp; //new Timer("children", 75000);
	
	@Override
	public FeatureVector getFeatures(final Frame f, final int head, final Sentence s) {
		
		full.start();
		final int n = s.size();
		Set<String> bag = new HashSet<String>();
		
		FeatureVector v = new FeatureVector();
		
		LexicalUnit headLU = s.getLU(head);
		String fs = "f=" + (debug ? f.getName() : f.getId());
		String fsc = f == Frame.nullFrame ? "nullFrame" : "nonNullFrame";
		
		b(v, "intercept");
		b(v, "frame=" + f.getName());
		b(v, fs + "-target-head=" + headLU.getFullString());
		b(v, fs + "-target-head=" + headLU.word);
		b(v, fs + "-target-head=" + headLU.pos);
		b(v, fs + "-sentence-length/2=" + (n/2));
		b(v, fs + "-sentence-length/3=" + (n/3));
		b(v, fs + "-sentence-length/4=" + (n/4));
		b(v, fs + "-sentence-length/5=" + (n/5));
		b(v, fs + "-sentence-length/6=" + (n/6));
		b(v, fs + "-sentence-length/7=" + (n/7));
		b(v, fs + "-sentence-length/8=" + (n/8));
		
		boolean matchesAnLU = false;
		final int nLU = f.numLexicalUnits();
		LexicalUnit whichLU = null;
		for(int i=0; i<nLU && !matchesAnLU; i++) {
			whichLU = f.getLexicalUnit(i);
			matchesAnLU |= headLU.equals(whichLU);
		}
		if(matchesAnLU) {
			b(v, "LU-match");
			b(v, "LU-match-" + whichLU.word);
			b(v, "LU-match-" + whichLU.pos);
			b(v, "LU-match" + fs);
			b(v, "LU-match" + fsc);
			b(v, "LU-match" + fs + "-" + whichLU.word);
			b(v, "LU-match" + fs + "-" + whichLU.pos);
			b(v, "LU-match" + fsc + "-" + whichLU.word);
			b(v, "LU-match" + fsc + "-" + whichLU.pos);
		}
		
		// parent words
		parentTimer.start();
		int parentIdx = s.governor(head);
		LexicalUnit parent = AbstractFeatures.getLUSafe(parentIdx, s);
		b(v, fs + "-parent=" + parent.getFullString());
		b(v, fs + "-parent=" + parent.word);
		b(v, fs + "-parent=" + parent.pos);
		b(v, fsc + "-parent=" + parent.getFullString());
		b(v, fsc + "-parent=" + parent.word);
		b(v, fsc + "-parent=" + parent.pos);
		int up = 1;
		boolean[] seen = new boolean[n];
		while(parentIdx >= 0 && !seen[parentIdx]) {
			parentIdx = s.governor(parentIdx);
			parent = AbstractFeatures.getLUSafe(parentIdx, s);
			b(v, fs + "-gov-by=" + parent.getFullString());
			b(v, fs + "-gov-by=" + parent.word);
			b(v, fs + "-gov-by=" + parent.word);
			b(v, fsc + "-gov-by=" + parent.getFullString());
			b(v, fsc + "-gov-by=" + parent.word);
			b(v, fsc + "-gov-by=" + parent.word);
			b(v, fs + "-up=" + up + "-gov-by=" + parent.getFullString());
			b(v, fs + "-up=" + up + "-gov-by=" + parent.word);
			b(v, fs + "-up=" + up + "-gov-by=" + parent.pos);
			if(parentIdx >= 0)
				seen[parentIdx] = true;
			up++;
		}
		parentTimer.stop();
		
		// direct children and descendants
		childTimer.start();
		Arrays.fill(seen, false);
		seen[head] = true;
		for(int i : s.childrenOf(head)) {
			seen[i] = true;
			LexicalUnit c = s.getLU(i);
			b(v, fs + "-child=" + c.getFullString());
			b(v, fs + "-child=" + c.word);
			b(v, fs + "-child=" + c.pos);
			b(v, fsc + "-child=" + c.getFullString());
			b(v, fsc + "-child=" + c.word);
			b(v, fsc + "-child=" + c.pos);
			allChildren(fs, i, 1, seen, s, v);
			allChildren(fsc, i, 1, seen, s, v);
		}
		childTimer.stop();
		
		
		// TODO wordnet
		
		
		// pairs of words in sentence
		bag.clear();
		for(int i=0; i<s.size(); i++) {
			String w = s.getWord(i);
			b(v, fs + "-\"" + w + "\"-appears-in-sentence");
			b(v, fsc + "-\"" + w + "\"-appears-in-sentence");
		}
		pairFeatures(fs, bag, v, "-in-sentence");
		pairFeatures(fsc, bag, v, "-in-sentence");
		
		// pairs of words on left
		bag.clear();
		for(int i=0; i<head; i++) {
			String w = s.getWord(i);
			b(v, fs + "-\"" + w + "\"-appears-to-the-left");
		}
		if(bag.size() == 0) b(v, fs + "-nothing-to-the-left");
		else pairFeatures(fs, bag, v, "-to-the-left");
		
		// pairs of words on right
		bag.clear();
		for(int i=head+1; i<s.size(); i++) {
			String w = s.getWord(i);
			b(v, fs + "-\"" + w + "\"-appears-to-the-right");
		}
		if(bag.size() == 0) b(v, fs + "-nothing-to-the-right");
		else pairFeatures(fs, bag, v, "-to-the-right");
		
		// word/pos to the left/right of the extent
		LexicalUnit l = AbstractFeatures.getLUSafe(head-1, s);
		b(v, fs + "-to-the-left=" + l.getFullString());
		b(v, fs + "-to-the-left=" + l.word);
		b(v, fs + "-to-the-left=" + l.pos);
		b(v, fsc + "-to-the-left=" + l.getFullString());
		b(v, fsc + "-to-the-left=" + l.word);
		b(v, fsc + "-to-the-left=" + l.pos);
		LexicalUnit r = AbstractFeatures.getLUSafe(head-1, s);
		b(v, fs + "-to-the-right=" + r.getFullString());
		b(v, fs + "-to-the-right=" + r.word);
		b(v, fs + "-to-the-right=" + r.pos);
		b(v, fsc + "-to-the-right=" + r.getFullString());
		b(v, fsc + "-to-the-right=" + r.word);
		b(v, fsc + "-to-the-right=" + r.pos);
		
		full.stop();
		return v;
	}
	
	private void allChildren(String fs, int head, int depth, boolean[] seen, Sentence s, FeatureVector v) {
		for(int i : s.childrenOf(head)) {
			if(!seen[i]) {
				seen[i] = true;
				LexicalUnit d = s.getLU(i);
				b(v, fs + "-descendant=" + d.word);
				b(v, fs + "-descendant=" + d.word + "-depth=" + depth);
				b(v, fs + "-descendant=" + d.pos + "-depth=" + depth);
				allChildren(fs, i, depth+1, seen, s, v);
			}
		}
	}
	
	private void pairFeatures(String fs, Set<String> items, FeatureVector v, String meta) {
		List<String> l = new ArrayList<String>();
		l.addAll(items);
		Collections.sort(l);
		int n = l.size();
		for(int i=0; i<n-1; i++) {
			String li = l.get(i);
			for(int j=i+1; j<n; j++) {
				String lj = l.get(j);
				b(v, fs + "-" + li + "-" + lj + "-appears" + meta);
			}
		}
	}

}
