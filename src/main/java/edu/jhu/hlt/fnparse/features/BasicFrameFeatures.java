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
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.util.Alphabet;

public final class BasicFrameFeatures extends AbstractFeatures<BasicFrameFeatures> implements F {
	
	public final boolean verbose = false;
	public final boolean debug = true;
	
	public Timer full = Timer.noOp; //new Timer("all", 75000);
	public Timer parentTimer = Timer.noOp; //new Timer("parent", 75000);
	public Timer childTimer = Timer.noOp; //new Timer("children", 75000);
	
	private static final String intercept = "intercept";
	private static final String frameFeatPrefix = "frame=";
	private static final String luMatch = "LU-match";
	private List<Integer> dontRegularize;
	
	public BasicFrameFeatures(Alphabet<String> featIdx) {
		super(featIdx);
		
		// compute the features that we don't want regularized
		FeatureVector noop = new FeatureVector();
		dontRegularize = new ArrayList<Integer>();
		dontRegularize.add(b(noop, intercept));
		dontRegularize.add(b(noop, luMatch));
//		for(Frame f : FrameIndex.getInstance().allFrames())
//			dontRegularize.add(b(noop, frameFeatPrefix, f.getName()));
	}
	
	@Override
	public List<Integer> dontRegularize() {
		assert dontRegularize.size() > 1;
		return dontRegularize;
	}
	
	@Override
	public FeatureVector getFeatures(final Frame f, final int head, final Sentence s) {
		
		full.start();
		final int n = s.size();
		Set<String> bag = new HashSet<String>();
		
		FeatureVector v = new FeatureVector();
		
		LexicalUnit headLU = s.getLU(head);
		String fs = "f=" + (debug ? f.getName() : f.getId());
		String fsc = f == Frame.nullFrame ? "nullFrame" : "nonNullFrame";
		
		b(v, intercept);
		b(v, frameFeatPrefix, f.getName());
		b(v, fs, "-target-head=", headLU.getFullString());
		b(v, fs, "-target-head=", headLU.word);
		b(v, fs, "-target-head=", headLU.pos);
		b(v, fs, "-sentence-length/2=", String.valueOf(n/2));
		b(v, fs, "-sentence-length/3=", String.valueOf(n/3));
		b(v, fs, "-sentence-length/4=", String.valueOf(n/4));
		b(v, fs, "-sentence-length/5=", String.valueOf(n/5));
		b(v, fs, "-sentence-length/6=", String.valueOf(n/6));
		b(v, fs, "-sentence-length/7=", String.valueOf(n/7));
		b(v, fs, "-sentence-length/8=", String.valueOf(n/8));
		
		boolean matchesAnLU = false;
		final int nLU = f.numLexicalUnits();
		LexicalUnit whichLU = null;
		for(int i=0; i<nLU && !matchesAnLU; i++) {
			whichLU = f.getLexicalUnit(i);
			matchesAnLU |= headLU.equals(whichLU);	// TODO lemmatized variants
		}
		if(matchesAnLU) {
			b(v, luMatch);
			b(v, luMatch, whichLU.word);
			b(v, luMatch, whichLU.pos);
			b(v, luMatch, fs);
			b(v, luMatch, fsc);
			b(v, luMatch, fs, whichLU.word);
			b(v, luMatch, fs, whichLU.pos);
			b(v, luMatch, fsc, whichLU.word);
			b(v, luMatch, fsc, whichLU.pos);
		}
		
		// parent words
		parentTimer.start();
		int parentIdx = s.governor(head);
		LexicalUnit parent = AbstractFeatures.getLUSafe(parentIdx, s);
		b(v, fs, "parent=", parent.getFullString());
		b(v, fs, "parent=", parent.word);
		b(v, fs, "parent=", parent.pos);
		b(v, fsc, "parent=", parent.getFullString());
		b(v, fsc, "parent=", parent.word);
		b(v, fsc, "parent=", parent.pos);
		int up = 1;
		boolean[] seen = new boolean[n];
		while(parentIdx >= 0 && !seen[parentIdx]) {
			parentIdx = s.governor(parentIdx);
			parent = AbstractFeatures.getLUSafe(parentIdx, s);
			b(v, fs, "gov-by=", parent.getFullString());
			b(v, fs, "gov-by=", parent.word);
			b(v, fs, "gov-by=", parent.word);
			b(v, fsc, "gov-by=", parent.getFullString());
			b(v, fsc, "gov-by=", parent.word);
			b(v, fsc, "gov-by=", parent.word);
			b(v, fs, "up=", String.valueOf(up), "-gov-by=", parent.getFullString());
			b(v, fs, "up=", String.valueOf(up), "-gov-by=", parent.word);
			b(v, fs, "up=", String.valueOf(up), "-gov-by=", parent.pos);
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
			b(v, fs, "child=", c.getFullString());
			b(v, fs, "child=", c.word);
			b(v, fs, "child=", c.pos);
			b(v, fsc, "child=", c.getFullString());
			b(v, fsc, "child=", c.word);
			b(v, fsc, "child=", c.pos);
			allChildren(fs, i, 1, seen, s, v);
			allChildren(fsc, i, 1, seen, s, v);
		}
		childTimer.stop();
		
		
		// TODO wordnet
		
		
		// pairs of words in sentence
		bag.clear();
		for(int i=0; i<s.size(); i++) {
			String w = s.getWord(i);
			b(v, fs, w, "appears-in-sentence");
			b(v, fsc, w, "appears-in-sentence");
		}
		pairFeatures(fs, bag, v, "-in-sentence");
		pairFeatures(fsc, bag, v, "-in-sentence");
		
		// pairs of words on left
		bag.clear();
		for(int i=0; i<head; i++) {
			String w = s.getWord(i);
			b(v, fs, w, "appears-to-the-left");
			b(v, fsc, w, "appears-to-the-left");
		}
		if(bag.size() == 0) {
			b(v, fs, "nothing-to-the-left");
			b(v, fsc, "nothing-to-the-left");
		}
		else pairFeatures(fs, bag, v, "-to-the-left");
		
		// pairs of words on right
		bag.clear();
		for(int i=head+1; i<s.size(); i++) {
			String w = s.getWord(i);
			b(v, fs, w, "appears-to-the-right");
			b(v, fsc, w, "appears-to-the-right");
		}
		if(bag.size() == 0) {
			b(v, fs, "nothing-to-the-right");
			b(v, fsc, "nothing-to-the-right");
		}
		else pairFeatures(fs, bag, v, "-to-the-right");
		
		// word/pos to the left/right of the extent
		LexicalUnit l = AbstractFeatures.getLUSafe(head-1, s);
		b(v, fs, "to-the-left=", l.getFullString());
		b(v, fs, "to-the-left=", l.word);
		b(v, fs, "to-the-left=", l.pos);
		b(v, fsc, "to-the-left=", l.getFullString());
		b(v, fsc, "to-the-left=", l.word);
		b(v, fsc, "to-the-left=", l.pos);
		LexicalUnit r = AbstractFeatures.getLUSafe(head-1, s);
		b(v, fs, "to-the-right=", r.getFullString());
		b(v, fs, "to-the-right=", r.word);
		b(v, fs, "to-the-right=", r.pos);
		b(v, fsc, "to-the-right=", r.getFullString());
		b(v, fsc, "to-the-right=", r.word);
		b(v, fsc, "to-the-right=", r.pos);
		
		//System.out.println("fv.size=" + v.size());
		full.stop();
		return v;
	}
	
	private void allChildren(String fs, int head, int depth, boolean[] seen, Sentence s, FeatureVector v) {
		for(int i : s.childrenOf(head)) {
			if(!seen[i]) {
				seen[i] = true;
				LexicalUnit d = s.getLU(i);
				b(v, fs, "descendant=", d.word);
				b(v, fs, "descendant=", d.word, "depth=", String.valueOf(depth));
				b(v, fs, "descendant=", d.pos, "depth=", String.valueOf(depth));
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
				b(v, fs, li, lj, "appears", meta);
			}
		}
	}

}
