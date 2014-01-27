package edu.jhu.hlt.fnparse.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.util.Alphabet;

public class BasicFrameFeatures implements FrameFeatures {

	private Alphabet<String> featIdx = new Alphabet<String>();
	public boolean verbose = false;
	
	@Override
	public String getDescription() { return "BasicTargetFeatures"; }

	@Override
	public String getFeatureName(int i) {
		String localName = featIdx.lookupObject(i);
		return getDescription() + ":" + localName;
	}
	
	public int head(Span s) {
		if(s.width() == 1)
			return s.start;
		else {
			System.err.println("warning! implement a real head finder");
			return s.end-1;
		}
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, Span extent, Sentence s) {
		
		int head = head(extent);
		Set<String> bag = new HashSet<String>();
		
		FeatureVector v = new FeatureVector();
		
		v.add(index("frame=" + f.getName()), 1d);
		v.add(index("numLU=" + f.numLexicalUnits()), 1d);
		v.add(index("target-head=" + s.getWord(head)), 1d);
		v.add(index("target-head-pos=" + s.getPos(head)), 1d);
		v.add(index("target-width=" + extent.width()), 1d);
		v.add(index("sentence-length=" + s.size()), 1d);
		
		LexicalUnit hypLU = s.getLU(head);
		boolean matchesAnLU = false;
		int n = f.numLexicalUnits();
		LexicalUnit whichLU = null;
		for(int i=0; i<n && !matchesAnLU; i++) {
			whichLU = f.getLexicalUnit(i);
			matchesAnLU |= hypLU.equals(whichLU);
		}
		if(matchesAnLU) {
			v.add(index("LU-match"), 1d);
			v.add(index("LU-match-"+whichLU), 1d);
			v.add(index("LU-match"+f.getName()), 1d);
			v.add(index("LU-match"+f.getName()+"-"+whichLU), 1d);
		}
		
		
		// TODO
		// popularity of frame
		// ambiguity of word
		// dependency parse features
		// wordnet
		
		
		// pairs of words in extent
		bag.clear();
		for(String w : s.wordsIn(extent)) {
			bag.add(w);
			v.add(index("\"" + w + "\"-appears-in-extent"), 1d);
		}
		pairFeatures(bag, v, "-in-extent");
		
		// pairs of POS in extent
		bag.clear();
		for(String p : s.posIn(extent)) {
			bag.add(p);
			v.add(index("\"" + p + "\"-appears-in-extent"), 1d);
		}
		pairFeatures(bag, v, "-in-extent");

		// pairs of words in sentence
		bag.clear();
		for(int i=0; i<s.size(); i++) {
			String w = s.getWord(i);
			v.add(index("\"" + w + "\"-appears-in-sentence"), 1d);
		}
		pairFeatures(bag, v, "-in-sentence");
		
		// pairs of POS in sentence
		bag.clear();
		for(int i=0; i<s.size(); i++) {
			String p = s.getPos(i);
			v.add(index("\"" + p + "\"-appears-in-sentence"), 1d);
		}
		pairFeatures(bag, v, "-in-sentence");
		
		// pairs of words on left
		bag.clear();
		for(int i=0; i<extent.start; i++) {
			String w = s.getWord(i);
			v.add(index("\"" + w + "\"-appears-to-the-left"), 1d);
		}
		if(bag.size() == 0) v.add(index("nothing-to-the-left"), 1d);
		else pairFeatures(bag, v, "-to-the-left");
		
		// pairs of pos on the left
		bag.clear();
		for(int i=0; i<extent.start; i++) {
			String p = s.getPos(i);
			v.add(index("\"" + p + "\"-appears-to-the-left"), 1d);
		}
		if(bag.size() == 0) v.add(index("nothing-to-the-left"), 1d);
		else pairFeatures(bag, v, "-to-the-left");
		
		// pairs of words on right
		bag.clear();
		for(int i=extent.end; i<s.size(); i++) {
			String w = s.getWord(i);
			v.add(index("\"" + w + "\"-appears-to-the-right"), 1d);
		}
		if(bag.size() == 0) v.add(index("nothing-to-the-right"), 1d);
		else pairFeatures(bag, v, "-to-the-right");
		
		// pairs of pos on the right
		bag.clear();
		for(int i=extent.end; i<s.size(); i++) {
			String p = s.getPos(i);
			v.add(index("\"" + p + "\"-appears-to-the-right"), 1d);
		}
		if(bag.size() == 0) v.add(index("nothing-to-the-right"), 1d);
		else pairFeatures(bag, v, "-to-the-right");
		
		// word/pos to the left/right of the extent
		v.add(index("word-to-the-left=" + (extent.start==0 ? "<S>" : s.getWord(extent.start-1))), 1d);
		v.add(index("pos-to-the-left=" + (extent.start==0 ? "<S>" : s.getPos(extent.start-1))), 1d);
		v.add(index("word-to-the-right=" + (extent.end==s.size() ? "</S>" : s.getWord(extent.end))), 1d);
		v.add(index("pos-to-the-right=" + (extent.end==s.size() ? "</S>" : s.getPos(extent.end))), 1d);
		
		return v;
	}
	
	private void pairFeatures(Set<String> items, FeatureVector v, String meta) {
		List<String> l = new ArrayList<String>();
		l.addAll(items);
		Collections.sort(l);
		int n = l.size();
		for(int i=0; i<n-1; i++)
			for(int j=i+1; j<n; j++)
				v.add(index("\""+l.get(i)+"\"-\""+l.get(j)+"\"-appears" + meta), 1d);
	}
	
	private int index(String featureName) {
		int s = featIdx.size();
		int i = featIdx.lookupIndex(featureName, true);
		if(verbose && s == i)
			System.out.println("[BasicFrameElemFeatures] new max = " + s);
		return i;
	}
	
	public int cardinality() { return 75000; }	// TODO

}
