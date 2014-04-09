package edu.jhu.hlt.fnparse.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.PosUtil;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.Features.F;
import edu.jhu.hlt.fnparse.inference.misc.Parser.ParserParams;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.item.IIndexWord;
import edu.mit.jwi.item.IPointer;
import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.ISynsetID;
import edu.mit.jwi.item.IWord;
import edu.mit.jwi.item.IWordID;
import edu.mit.jwi.morph.WordnetStemmer;

public final class BasicFrameFeatures extends AbstractFeatures<BasicFrameFeatures> implements F {
	
	private static final long serialVersionUID = 1L;
	
	private static final String intercept = "intercept";
	private static final String frameFeatPrefix = "frame=";
	private static final String luMatch = "LU-match";
	
	public transient Timer full = Timer.noOp; //new Timer("all", 75000);
	public transient Timer parentTimer = Timer.noOp; //new Timer("parent", 75000);
	public transient Timer childTimer = Timer.noOp; //new Timer("children", 75000);

	private ParserParams params;
	private List<Integer> dontRegularize;
	private boolean bowWithDirection = false;
	private boolean allowDifferentPosLU = false;
	private boolean allowDifferentPosLEX = false;
	public boolean debug = false;
	
	public BasicFrameFeatures(ParserParams params) {
		super(params.featIdx);
		this.params = params;
		
		// compute the features that we don't want regularized
		FeatureVector noop = new FeatureVector();
		dontRegularize = new ArrayList<Integer>();
		dontRegularize.add(b(noop, intercept));
		dontRegularize.add(b(noop, luMatch));
		for(Frame f : FrameIndex.getInstance().allFrames()) {
			dontRegularize.add(b(noop, frameFeatPrefix, f.getName()));
		}
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
		String fs = "f=" + (params.fastFeatNames ? f.getId() : f.getName());
		String fsc = f == Frame.nullFrame ? "nullFrame" : "nonNullFrame";
		

		Map<String, Double> lexicalMatchVariants = new HashMap<String, Double>();
		lexicalMatchVariants.put("vanilla", 2d);
		lexicalMatchVariants.put(fs, 1d);
		lexicalMatchVariants.put(fsc, 1d);
		lexicalMatchVariants.put(headLU.word, 1d);
		lexicalMatchVariants.put(headLU.pos, 2d);
		lexicalMatchVariants.put(fs + "-" + headLU.pos, 1d);
		lexicalMatchVariants.put(fsc + "-" + headLU.pos, 1d);
		
		
		// start of features
		b(v, intercept);
		b(v, frameFeatPrefix, f.getName());
		b(v, 0.5d, fs, "-target-head=", headLU.getFullString());
		b(v, 1d,   fs, "-target-head=", headLU.word);
		b(v, 2d,   fs, "-target-head=", headLU.pos);
		b(v, 0.1d, fs, "-sentence-length/2=", String.valueOf(n/2));
		b(v, 0.2d, fs, "-sentence-length/3=", String.valueOf(n/3));
		b(v, 0.3d, fs, "-sentence-length/4=", String.valueOf(n/4));
		b(v, 0.4d, fs, "-sentence-length/5=", String.valueOf(n/5));
		b(v, 0.5d, fs, "-sentence-length/6=", String.valueOf(n/6));
		b(v, 0.6d, fs, "-sentence-length/7=", String.valueOf(n/7));
		b(v, 0.7d, fs, "-sentence-length/8=", String.valueOf(n/8));
		
		
		// matches a Lexical Unit for this Frame?
		final int nLU = f.numLexicalUnits();
		for(int i=0; i<nLU; i++) {
			LexicalUnit lu = f.getLexicalUnit(i);
			lexicalMatch(v, headLU, lu, ResourceForPos.LU, lexicalMatchVariants, allowDifferentPosLU);
		}
		
		
		// match any of the prototypes from the LEX examples?
		List<FrameInstance> prototypes = params.targetPruningData.getPrototypesByFrame().get(f);
		if(prototypes != null) {
			for(FrameInstance proto : prototypes) {
				Span t = proto.getTarget();
				if(t.width() > 1) continue;
				LexicalUnit protoLU = proto.getSentence().getLU(t.start);
				lexicalMatch(v, headLU, protoLU, ResourceForPos.LEX, lexicalMatchVariants, allowDifferentPosLEX);
			}
		}
		else b(v, "no-LEX-prototypes");

		
		if(params.useSyntaxFeatures) {
		
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
		}
		
		
		// pairs of words in sentence
		bag.clear();
		for(int i=0; i<s.size(); i++) {
			String w = s.getWord(i);
			b(v, 0.75d, fs, w, "appears-in-sentence");
			b(v, 0.75d, fsc, w, "appears-in-sentence");
		}
		pairFeatures(fs, 0.75d, bag, v, "-in-sentence");
		pairFeatures(fsc, 0.75d, bag, v, "-in-sentence");
		
		if(bowWithDirection) {
			// pairs of words on left
			bag.clear();
			for(int i=0; i<head; i++) {
				String w = s.getWord(i);
				b(v, 0.5d, fs, w, "appears-to-the-left");
				b(v, 0.5d, fsc, w, "appears-to-the-left");
			}
			if(bag.size() == 0) {
				b(v, 0.5d, fs, "nothing-to-the-left");
				b(v, 0.5d, fsc, "nothing-to-the-left");
			}
			else pairFeatures(fs, 0.5d, bag, v, "-to-the-left");

			// pairs of words on right
			bag.clear();
			for(int i=head+1; i<s.size(); i++) {
				String w = s.getWord(i);
				b(v, 0.5d, fs, w, "appears-to-the-right");
				b(v, 0.5d, fsc, w, "appears-to-the-right");
			}
			if(bag.size() == 0) {
				b(v, 0.5d, fs, "nothing-to-the-right");
				b(v, 0.5d, fsc, "nothing-to-the-right");
			}
			else pairFeatures(fs, 0.5d, bag, v, "-to-the-right");
		}
		
		// word/pos to the left/right of the extent
		LexicalUnit l = AbstractFeatures.getLUSafe(head-1, s);
		LexicalUnit ll = AbstractFeatures.getLUSafe(head-2, s);
		b(v, fs, "to-the-left=", l.getFullString());
		b(v, fs, "to-the-left=", l.word);
		b(v, fs, "to-the-left=", l.pos);
		b(v, fs, "to-the-left=", ll.word, l.getFullString());
		b(v, fs, "to-the-left=", ll.pos, l.getFullString());
		b(v, fs, "to-the-left=", ll.word, l.word);
		b(v, fs, "to-the-left=", ll.pos, l.word);
		b(v, fs, "to-the-left=", ll.pos, l.pos);
		b(v, fs, "to-the-left=", ll.word, l.pos);
		b(v, fsc, "to-the-left=", l.getFullString());
		b(v, fsc, "to-the-left=", l.word);
		b(v, fsc, "to-the-left=", l.pos);
		b(v, fsc, "to-the-left=", ll.word, l.getFullString());
		b(v, fsc, "to-the-left=", ll.pos, l.getFullString());
		b(v, fsc, "to-the-left=", ll.word, l.word);
		b(v, fsc, "to-the-left=", ll.pos, l.word);
		b(v, fsc, "to-the-left=", ll.pos, l.pos);
		b(v, fsc, "to-the-left=", ll.word, l.pos);
		
		LexicalUnit r = AbstractFeatures.getLUSafe(head+1, s);
		LexicalUnit rr = AbstractFeatures.getLUSafe(head+2, s);
		b(v, fs, "to-the-right=", r.getFullString());
		b(v, fs, "to-the-right=", r.word);
		b(v, fs, "to-the-right=", r.pos);
		b(v, fs, "to-the-right=", rr.word, r.getFullString());
		b(v, fs, "to-the-right=", rr.pos, r.getFullString());
		b(v, fs, "to-the-right=", rr.word, r.word);
		b(v, fs, "to-the-right=", rr.pos, r.word);
		b(v, fs, "to-the-right=", rr.pos, r.pos);
		b(v, fs, "to-the-right=", rr.word, r.pos);
		b(v, fsc, "to-the-right=", r.getFullString());
		b(v, fsc, "to-the-right=", r.word);
		b(v, fsc, "to-the-right=", r.pos);
		b(v, fsc, "to-the-right=", rr.word, r.getFullString());
		b(v, fsc, "to-the-right=", rr.pos, r.getFullString());
		b(v, fsc, "to-the-right=", rr.word, r.word);
		b(v, fsc, "to-the-right=", rr.pos, r.word);
		b(v, fsc, "to-the-right=", rr.pos, r.pos);
		b(v, fsc, "to-the-right=", rr.word, r.pos);
		
		//System.out.println("fv.size=" + v.size());
		full.stop();
		return v;
	}
	
	private transient IRAMDictionary dict;
	private IRAMDictionary getDict() {
		if(dict == null)
			dict = params.targetPruningData.getWordnetDict();
		return dict;
	}
	
	private transient WordnetStemmer stemmer;
	private WordnetStemmer getStemmer() {
		if(stemmer == null)
			stemmer = new WordnetStemmer(getDict());
		return stemmer;
	}
	
	private static enum ResourceForPos { LU, LEX }
	
	private void lexicalMatch(FeatureVector v, LexicalUnit inTarget, LexicalUnit inResource,
			ResourceForPos resource, Map<String, Double> descriptions, boolean allowDifferentPOS) {

		// sort out the POS tags
		edu.mit.jwi.item.POS inTargetTag = PosUtil.ptb2wordNet(inTarget.pos);
		edu.mit.jwi.item.POS inResourceTag;
		switch(resource) {
		case LU:
			inResourceTag = PosUtil.ptb2wordNet(inResource.pos);
			break;
		case LEX:
			inResourceTag = PosUtil.fn2wordNet(inResource.pos);
			break;
		default:
			throw new RuntimeException("resource = " + resource);
		}
		
		// compute the multiplier for vanilla features
		double mult = 0d;
		boolean posMatch = inTargetTag != null && inTargetTag.equals(inResourceTag);
		boolean exactMatch = posMatch && inTarget.word.equals(inResource.word); 
		if(exactMatch) mult = 1d;
		else if(posMatch) {
			if(inTarget.word.equalsIgnoreCase(inResource.word)) {
				// no case match
				mult = 0.75d;
			}
			else {
				// prefix match
				int m = longestCommonPrefix(inTarget.word.toLowerCase(), inResource.word.toLowerCase());
				if(m > 2)
					mult = Math.min(0.1d * (m-2), 0.75d);
			}
		}
		
		// vanilla match
		for(Map.Entry<String, Double> x : descriptions.entrySet())
			b(v, x.getValue() * mult, resource.toString(), x.getKey());
		
		
		// wordnet match
		boolean wnMadeIt = false;
		boolean tried = false;
		wnMatch:
		if(inTargetTag != null && inResourceTag != null && (allowDifferentPOS || inTargetTag.equals(inResourceTag))) {
			tried = true;
			IRAMDictionary d = getDict();
			WordnetStemmer s = getStemmer();

			List<String> rstems = s.findStems(inResource.word, inResourceTag);
			List<String> tstems = s.findStems(inTarget.word, inTargetTag);
			if(rstems == null || tstems == null || rstems.isEmpty() || tstems.isEmpty())
				break wnMatch;
			
			IIndexWord ti = d.getIndexWord(tstems.get(0), inTargetTag);
			IIndexWord ri = d.getIndexWord(rstems.get(0), inResourceTag);
			if(ti == null || ri == null || ti.getWordIDs().isEmpty() || ri.getWordIDs().isEmpty())
				break wnMatch;
			
			IWordID t = ti.getWordIDs().get(0);
			IWordID r = ri.getWordIDs().get(0);
			IWord tw = d.getWord(t);
			IWord rw = d.getWord(r);
			wnMadeIt = true;
			
			// same synset
			ISynset tss = tw.getSynset();
			if(tss.getWords().contains(rw)) {
				for(Map.Entry<String, Double> x : descriptions.entrySet()) {
					b(v, x.getValue(),      resource.toString(), x.getKey(), "same-synset");
					b(v, x.getValue() / 2d, resource.toString(), x.getKey(), "same-synset", inTarget.pos);
				}
			}
			
			// related synsets
			Map<IPointer, List<ISynsetID>> relMap = tw.getSynset().getRelatedMap();
			for(Entry<IPointer, List<ISynsetID>> rel : relMap.entrySet()) {
				IPointer relation = rel.getKey();
				for(ISynsetID ssid : rel.getValue()) {
					ISynset ss = d.getSynset(ssid);
					boolean fires = ss.getWords().contains(rw);
					if(fires) {
						if(debug) {
							System.out.printf("[lexicalMatch] %-10s %-15s %-15s holds\n",
									relation.getName(), inTarget.getFullString(), inResource.getFullString());
						}
						for(Map.Entry<String, Double> x : descriptions.entrySet()) {
							b(v, x.getValue(),      "wordnet", resource.toString(), x.getKey(), relation.getName());
							b(v, x.getValue() / 2d, "wordnet", resource.toString(), x.getKey(), relation.getName(), inTarget.pos);
						}
					}
				}
			}
			if(debug && relMap.isEmpty()) {
				System.out.println("[lexicalMatch] no WordNet relation between " +
						inTarget.getFullString() + " and " + inResource.getFullString());
			}
		}
		if(debug && !wnMadeIt && tried) {
			System.err.println("[lexicalMatch] (" + resource + ") failed on WordNet relation between " +
					inTarget.getFullString() + " and " + inResource.getFullString());
		}
	}
	
	private int longestCommonPrefix(String a, String b) {
		int n = Math.min(a.length(), b.length());
		int m;
		for(m=0; m<n && a.charAt(m) == b.charAt(m); m++);
		return m;
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
	
	private void pairFeatures(String fs, double weight, Set<String> items, FeatureVector v, String meta) {
		List<String> l = new ArrayList<String>();
		l.addAll(items);
		Collections.sort(l);
		int n = l.size();
		for(int i=0; i<n-1; i++) {
			String li = l.get(i);
			for(int j=i+1; j<n; j++) {
				String lj = l.get(j);
				b(v, weight, fs, li, lj, "appears", meta);
			}
		}
	}

}
