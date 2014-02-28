package edu.jhu.hlt.fnparse.features;

import java.util.ArrayList;
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

	@Override
	public FeatureVector getFeatures(Frame f, int head, Sentence s) {
		
		final int n = s.size();
		Set<String> bag = new HashSet<String>();
		
		FeatureVector v = new FeatureVector();
		
		String fs = "f=" + (debug ? f.getName() : f.getId());
		String fsc = f == Frame.nullFrame ? "nullFrame" : "nonNullFrame";
		
		b(v, "frame=" + f.getName());
		b(v, "numLU=" + f.numLexicalUnits());
		b(v, fs + "-target-head=" + s.getWord(head));
		b(v, fs + "-target-head-pos=" + s.getPos(head));
		b(v, fs + "-sentence-length=" + s.size());
		
		LexicalUnit hypLU = s.getLU(head);
		boolean matchesAnLU = false;
		final int nLU = f.numLexicalUnits();
		LexicalUnit whichLU = null;
		for(int i=0; i<nLU && !matchesAnLU; i++) {
			whichLU = f.getLexicalUnit(i);
			matchesAnLU |= hypLU.equals(whichLU);
		}
		if(matchesAnLU) {
			b(v, "LU-match");
			b(v, "LU-match-"+whichLU);
			b(v, "LU-match"+fs);
			b(v, "LU-match"+fsc);
			b(v, "LU-match"+fs+"-"+whichLU);
			b(v, "LU-match"+fsc+"-"+whichLU);
		}
		
		// parent words
		int parentIdx = s.governor(head);
		LexicalUnit parent = AbstractFeatures.getLUSafe(parentIdx, s);
		b(v, fs + "-parent=" + parent.getFullString());
		b(v, fs + "-parent=" + parent.word);
		b(v, fs + "-parent=" + parent.pos);
		b(v, fsc + "-parent=" + parent.getFullString());
		b(v, fsc + "-parent=" + parent.word);
		b(v, fsc + "-parent=" + parent.pos);
//		int up = 1;
//		while(parentIdx >= 0) {
//			parentIdx = s.governor(parentIdx);
//			parent = AbstractFeatures.getLUSafe(parentIdx, s);
//			b(v, fs + "-gov-by=" + parent.getFullString());
//			b(v, fs + "-gov-by=" + parent.word);
//			b(v, fs + "-gov-by=" + parent.word);
//			b(v, fsc + "-gov-by=" + parent.getFullString());
//			b(v, fsc + "-gov-by=" + parent.word);
//			b(v, fsc + "-gov-by=" + parent.word);
//			b(v, fs + "-up=" + up + "-gov-by=" + parent.getFullString());
//			b(v, fs + "-up=" + up + "-gov-by=" + parent.word);
//			b(v, fs + "-up=" + up + "-gov-by=" + parent.pos);
//			up++;
//		}
//		
//		// direct children and descendants
//		for(int i=0; i<n; i++) {
//			int gov_i = s.governor(i);
//			if(head == gov_i) {
//				LexicalUnit c = s.getLU(i);
//				b(v, fs + "-child=" + c.getFullString());
//				b(v, fs + "-child=" + c.word);
//				b(v, fs + "-child=" + c.pos);
//				b(v, fsc + "-child=" + c.getFullString());
//				b(v, fsc + "-child=" + c.word);
//				b(v, fsc + "-child=" + c.pos);
//				allChildren(fs, i, 1, s, v);
//				allChildren(fsc, i, 1, s, v);
//			}
//		}
		
		
		
		
		
		// TODO
		// popularity of frame
		// ambiguity of word
		// wordnet
		
		
		// pairs of words in sentence
		bag.clear();
		for(int i=0; i<s.size(); i++) {
			String w = s.getWord(i);
			b(v, fs + "-\"" + w + "\"-appears-in-sentence");
			b(v, fsc + "-\"" + w + "\"-appears-in-sentence");
		}
		pairFeatures(fs, bag, v, "-in-sentence");
		pairFeatures(fsc, bag, v, "-in-sentence");
		
//		// pairs of POS in sentence
//		bag.clear();
//		for(int i=0; i<s.size(); i++) {
//			String p = s.getPos(i);
//			b(v, fs + "-\"" + p + "\"-appears-in-sentence");
//		}
//		pairFeatures(f, bag, v, "-in-sentence");
		
		// pairs of words on left
		bag.clear();
		for(int i=0; i<head; i++) {
			String w = s.getWord(i);
			b(v, fs + "-\"" + w + "\"-appears-to-the-left");
		}
		if(bag.size() == 0) b(v, fs + "-nothing-to-the-left");
		else pairFeatures(fs, bag, v, "-to-the-left");
		
//		// pairs of pos on the left
//		bag.clear();
//		for(int i=0; i<head; i++) {
//			String p = s.getPos(i);
//			b(v, fs + "-\"" + p + "\"-appears-to-the-left");
//		}
//		if(bag.size() == 0) b(v, fs + "-nothing-to-the-left");
//		else pairFeatures(f, bag, v, "-to-the-left");
		
		// pairs of words on right
		bag.clear();
		for(int i=head+1; i<s.size(); i++) {
			String w = s.getWord(i);
			b(v, fs + "-\"" + w + "\"-appears-to-the-right");
		}
		if(bag.size() == 0) b(v, fs + "-nothing-to-the-right");
		else pairFeatures(fs, bag, v, "-to-the-right");
		
//		// pairs of pos on the right
//		bag.clear();
//		for(int i=head+1; i<s.size(); i++) {
//			String p = s.getPos(i);
//			b(v, fs + "-\"" + p + "\"-appears-to-the-right");
//		}
//		if(bag.size() == 0) b(v, fs + "-nothing-to-the-right");
//		else pairFeatures(f, bag, v, "-to-the-right");
		
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
		
		return v;
	}
	
	private void allChildren(String fs, int head, int depth, Sentence s, FeatureVector v) {
		int n = s.size();
		for(int i=0; i<n; i++) {
			int g = s.governor(i);
			if(g == head) {
				LexicalUnit d = s.getLU(i);
				b(v, fs + "-descendant=" + d.word);
				b(v, fs + "-descendant=" + d.word + "-depth=" + depth);
				b(v, fs + "-descendant=" + d.pos + "-depth=" + depth);
				allChildren(fs, i, depth+1, s, v);
			}
		}
	}
	
	private void pairFeatures(String fs, Set<String> items, FeatureVector v, String meta) {
		List<String> l = new ArrayList<String>();
		l.addAll(items);
		Collections.sort(l);
		int n = l.size();
		for(int i=0; i<n-1; i++)
			for(int j=i+1; j<n; j++)
				b(v, fs + "-\""+l.get(i)+"\"-\""+l.get(j)+"\"-appears" + meta);
	}

}
