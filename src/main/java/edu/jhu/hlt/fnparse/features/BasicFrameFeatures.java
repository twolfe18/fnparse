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
import edu.jhu.hlt.fnparse.features.indexing.BasicBob;
import edu.jhu.hlt.fnparse.features.indexing.Joe;
import edu.jhu.hlt.fnparse.features.indexing.JoeInfo;
import edu.jhu.hlt.fnparse.features.indexing.SuperBob;
import edu.jhu.util.Alphabet;

public class BasicFrameFeatures implements edu.jhu.hlt.fnparse.features.Features.F, Joe<JoeInfo> {

	private BasicBob bob;
	private Alphabet<String> featIdx;
	public boolean verbose = false;
	
	public BasicFrameFeatures() {
		bob = (BasicBob) SuperBob.getBob(this);
		featIdx = bob.trackMyAlphabet(this);
	}

	@Override
	public FeatureVector getFeatures(Frame f, int head, Sentence s) {
		
		Set<String> bag = new HashSet<String>();
		
		FeatureVector v = new FeatureVector();
		
		String fs = "f" + f.getId();
		
		v.add(index("frame=" + f.getName()), 1d);
		v.add(index("numLU=" + f.numLexicalUnits()), 1d);
		v.add(index(fs + "-target-head=" + s.getWord(head)), 1d);
		v.add(index(fs + "target-head-pos=" + s.getPos(head)), 1d);
		v.add(index(fs + "sentence-length=" + s.size()), 1d);
		
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
		
		


		// pairs of words in sentence
		bag.clear();
		for(int i=0; i<s.size(); i++) {
			String w = s.getWord(i);
			v.add(index(fs + "-\"" + w + "\"-appears-in-sentence"), 1d);
		}
		pairFeatures(f, bag, v, "-in-sentence");
		
		// pairs of POS in sentence
		bag.clear();
		for(int i=0; i<s.size(); i++) {
			String p = s.getPos(i);
			v.add(index("\"" + p + "\"-appears-in-sentence"), 1d);
		}
		pairFeatures(f, bag, v, "-in-sentence");
		
		// pairs of words on left
		bag.clear();
		for(int i=0; i<head; i++) {
			String w = s.getWord(i);
			v.add(index("\"" + w + "\"-appears-to-the-left"), 1d);
		}
		if(bag.size() == 0) v.add(index("nothing-to-the-left"), 1d);
		else pairFeatures(f, bag, v, "-to-the-left");
		
		// pairs of pos on the left
		bag.clear();
		for(int i=0; i<head; i++) {
			String p = s.getPos(i);
			v.add(index("\"" + p + "\"-appears-to-the-left"), 1d);
		}
		if(bag.size() == 0) v.add(index("nothing-to-the-left"), 1d);
		else pairFeatures(f, bag, v, "-to-the-left");
		
		// pairs of words on right
		bag.clear();
		for(int i=head+1; i<s.size(); i++) {
			String w = s.getWord(i);
			v.add(index("\"" + w + "\"-appears-to-the-right"), 1d);
		}
		if(bag.size() == 0) v.add(index("nothing-to-the-right"), 1d);
		else pairFeatures(f, bag, v, "-to-the-right");
		
		// pairs of pos on the right
		bag.clear();
		for(int i=head+1; i<s.size(); i++) {
			String p = s.getPos(i);
			v.add(index("\"" + p + "\"-appears-to-the-right"), 1d);
		}
		if(bag.size() == 0) v.add(index("nothing-to-the-right"), 1d);
		else pairFeatures(f, bag, v, "-to-the-right");
		
		// word/pos to the left/right of the extent
		v.add(index("word-to-the-left=" + (head==0 ? "<S>" : s.getWord(head-1))), 1d);
		v.add(index("pos-to-the-left=" + (head==0 ? "<S>" : s.getPos(head-1))), 1d);
		v.add(index("word-to-the-right=" + (head==s.size()-1 ? "</S>" : s.getWord(head+1))), 1d);
		v.add(index("pos-to-the-right=" + (head==s.size()-1 ? "</S>" : s.getPos(head+1))), 1d);
		
		return bob.doYourThing(v, this);
	}
	
	private void pairFeatures(Frame f, Set<String> items, FeatureVector v, String meta) {
		List<String> l = new ArrayList<String>();
		l.addAll(items);
		Collections.sort(l);
		int n = l.size();
		for(int i=0; i<n-1; i++)
			for(int j=i+1; j<n; j++)
				v.add(index("f" + f.getId() + "-\""+l.get(i)+"\"-\""+l.get(j)+"\"-appears" + meta), 1d);
	}
	
	private int index(String featureName) {
		int s = featIdx.size();
		int i = featIdx.lookupIndex(featureName, true);
		if(verbose && s == i)
			System.out.println("[BasicFrameElemFeatures] new max = " + s);
		return i;
	}
	

	
	private JoeInfo joeInfo;
	
	@Override
	public String getJoeName() {
		return this.getClass().getName();
	}

	@Override
	public void storeJoeInfo(JoeInfo info) { joeInfo = info; }

	@Override
	public JoeInfo getJoeInfo() { return joeInfo; }

}
