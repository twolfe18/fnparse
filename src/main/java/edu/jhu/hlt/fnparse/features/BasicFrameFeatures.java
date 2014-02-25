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

public class BasicFrameFeatures extends AbstractFeatures<BasicFrameFeatures> implements F {
	
	public BasicFrameFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}

	public boolean verbose = false;

	@Override
	public FeatureVector getFeatures(Frame f, int head, Sentence s) {
		
		Set<String> bag = new HashSet<String>();
		
		FeatureVector v = new FeatureVector();
		
		String fs = "f" + f.getId();
		
		b(v, "frame=" + f.getName());
		b(v, "numLU=" + f.numLexicalUnits());
		b(v, fs + "-target-head=" + s.getWord(head));
		b(v, fs + "-target-head-pos=" + s.getPos(head));
		b(v, fs + "-sentence-length=" + s.size());
		
		LexicalUnit hypLU = s.getLU(head);
		boolean matchesAnLU = false;
		int n = f.numLexicalUnits();
		LexicalUnit whichLU = null;
		for(int i=0; i<n && !matchesAnLU; i++) {
			whichLU = f.getLexicalUnit(i);
			matchesAnLU |= hypLU.equals(whichLU);
		}
		if(matchesAnLU) {
			b(v, "LU-match");
			b(v, "LU-match-"+whichLU);
			b(v, "LU-match"+f.getName());
			b(v, "LU-match"+f.getName()+"-"+whichLU);
		}
		
		
		// TODO
		// popularity of frame
		// ambiguity of word
		// dependency parse features
		// wordnet
		
		


		// pairs of words in sentence
		bag.clear();
		for(int i=0; i<s.size(); i++) {
			String w = s.getWord(i);
			b(v, fs + "-\"" + w + "\"-appears-in-sentence");
		}
		pairFeatures(f, bag, v, "-in-sentence");
		
		// pairs of POS in sentence
		bag.clear();
		for(int i=0; i<s.size(); i++) {
			String p = s.getPos(i);
			b(v, fs + "-\"" + p + "\"-appears-in-sentence");
		}
		pairFeatures(f, bag, v, "-in-sentence");
		
		// pairs of words on left
		bag.clear();
		for(int i=0; i<head; i++) {
			String w = s.getWord(i);
			b(v, fs + "-\"" + w + "\"-appears-to-the-left");
		}
		if(bag.size() == 0) b(v, fs + "-nothing-to-the-left");
		else pairFeatures(f, bag, v, "-to-the-left");
		
		// pairs of pos on the left
		bag.clear();
		for(int i=0; i<head; i++) {
			String p = s.getPos(i);
			b(v, fs + "-\"" + p + "\"-appears-to-the-left");
		}
		if(bag.size() == 0) b(v, fs + "-nothing-to-the-left");
		else pairFeatures(f, bag, v, "-to-the-left");
		
		// pairs of words on right
		bag.clear();
		for(int i=head+1; i<s.size(); i++) {
			String w = s.getWord(i);
			b(v, fs + "-\"" + w + "\"-appears-to-the-right");
		}
		if(bag.size() == 0) b(v, fs + "-nothing-to-the-right");
		else pairFeatures(f, bag, v, "-to-the-right");
		
		// pairs of pos on the right
		bag.clear();
		for(int i=head+1; i<s.size(); i++) {
			String p = s.getPos(i);
			b(v, fs + "-\"" + p + "\"-appears-to-the-right");
		}
		if(bag.size() == 0) b(v, fs + "-nothing-to-the-right");
		else pairFeatures(f, bag, v, "-to-the-right");
		
		// word/pos to the left/right of the extent
		b(v, fs + "-word-to-the-left=" + (head==0 ? "<S>" : s.getWord(head-1)));
		b(v, fs + "-pos-to-the-left=" + (head==0 ? "<S>" : s.getPos(head-1)));
		b(v, fs + "-word-to-the-right=" + (head==s.size()-1 ? "</S>" : s.getWord(head+1)));
		b(v, fs + "-pos-to-the-right=" + (head==s.size()-1 ? "</S>" : s.getPos(head+1)));
		
		return v;
	}
	
	private void pairFeatures(Frame f, Set<String> items, FeatureVector v, String meta) {
		List<String> l = new ArrayList<String>();
		l.addAll(items);
		Collections.sort(l);
		int n = l.size();
		for(int i=0; i<n-1; i++)
			for(int j=i+1; j<n; j++)
				b(v, "f" + f.getId() + "-\""+l.get(i)+"\"-\""+l.get(j)+"\"-appears" + meta);
	}

}
