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
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.PosUtil;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.Path.EdgeType;
import edu.jhu.hlt.fnparse.features.Path.NodeType;
import edu.jhu.hlt.fnparse.inference.HasParserParams;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.item.IIndexWord;
import edu.mit.jwi.item.IPointer;
import edu.mit.jwi.item.ISynset;
import edu.mit.jwi.item.ISynsetID;
import edu.mit.jwi.item.IWord;
import edu.mit.jwi.item.IWordID;
import edu.mit.jwi.morph.WordnetStemmer;

public final class BasicFrameFeatures
		extends AbstractFeatures<BasicFrameFeatures>
		implements Features.F {
	private static final long serialVersionUID = 1L;

	private static final String intercept = "intercept";
	private static final String frameFeatPrefix = "frame=";

	private TargetPruningData targetPruningData;
	private boolean bowWithDirection = false;
	private boolean allowDifferentPosLU = false;
	private boolean allowDifferentPosLEX = false;

	public BasicFrameFeatures(HasParserParams globalParams) {
		super(globalParams);
		targetPruningData = TargetPruningData.getInstance();
		weightingPower = 0.5d;
	}

	@Override
	public void featurize(
			FeatureVector v,
			Refinements refs,
			int head,
			Frame f,
			Sentence s) {
		final int n = s.size();
		Set<String> bag = new HashSet<String>();

		LexicalUnit headLU = s.getLU(head);
		String fs = "f=" + (this.useFastFeaturenames ? f.getId() : f.getName());
		String fsc = f == Frame.nullFrame ? "nullFrame" : "nonNullFrame";

		Map<String, Double> lexicalMatchVariants = new HashMap<>();
		lexicalMatchVariants.put("vanilla", 2d);
		lexicalMatchVariants.put(fs, 1d);
		lexicalMatchVariants.put(fsc, 1d);
		lexicalMatchVariants.put(headLU.word, 1d);
		lexicalMatchVariants.put(headLU.pos, 2d);
		lexicalMatchVariants.put(fs + "-" + headLU.pos, 1d);
		lexicalMatchVariants.put(fsc + "-" + headLU.pos, 1d);

		// Start of features
		b(v, refs, 10d, intercept);
		b(v, refs, 4d, frameFeatPrefix, f.getName());
		b(v, refs, 0.5d, fs, "-target-head=", headLU.getFullString());
		b(v, refs, 1d,   fs, "-target-head=", headLU.word);
		b(v, refs, 2d,   fs, "-target-head=", headLU.pos);
		b(v, refs, 0.3d, fs, "-sentence-length/4=", String.valueOf(n/4));
		b(v, refs, 0.3d, fsc, "-sentence-length/4=", String.valueOf(n/4));
		b(v, refs, 0.7d, fs, "-sentence-length/8=", String.valueOf(n/8));
		b(v, refs, 0.7d, fsc, "-sentence-length/8=", String.valueOf(n/8));

		// Matches a Lexical Unit for this Frame?
		final int nLU = f.numLexicalUnits();
		for (int i=0; i<nLU; i++) {
			LexicalUnit lu = f.getLexicalUnit(i);
			lexicalMatch(v, headLU, lu, ResourceForPos.LU,
					lexicalMatchVariants, allowDifferentPosLU, refs);
		}

		// Match any of the prototypes from the LEX examples?
		List<FrameInstance> prototypes =
				targetPruningData.getPrototypesByFrame().get(f);
		if (prototypes != null) {
			for(FrameInstance proto : prototypes) {
				Span t = proto.getTarget();
				if(t.width() > 1) continue;
				LexicalUnit protoLU = proto.getSentence().getLU(t.start);
				lexicalMatch(v, headLU, protoLU, ResourceForPos.LEX,
						lexicalMatchVariants, allowDifferentPosLEX, refs);
			}
		} else {
			b(v, refs, "no-LEX-prototypes");
		}

		if (globalParams.getParserParams().useSyntaxFeatures) {
			// Parent words
			int parentIdx = s.governor(head);
			LexicalUnit parent = AbstractFeatures.getLUSafe(parentIdx, s);
			b(v, refs, fs, "parent=", parent.getFullString());
			b(v, refs, fs, "parent=", parent.word);
			b(v, refs, fs, "parent=", parent.pos);
			b(v, refs, fsc, "parent=", parent.getFullString());
			b(v, refs, fsc, "parent=", parent.word);
			b(v, refs, fsc, "parent=", parent.pos);

			// Direct children and descendants
			for (int i : s.childrenOf(head)) {
				LexicalUnit c = s.getLU(i);
				b(v, refs, fs, "child=", c.getFullString());
				b(v, refs, fs, "child=", c.word);
				b(v, refs, fs, "child=", c.pos);
				b(v, refs, fsc, "child=", c.getFullString());
				b(v, refs, fsc, "child=", c.word);
				b(v, refs, fsc, "child=", c.pos);
				//allChildren(fs, i, 1, seen, s, v, refs);
				//allChildren(fsc, i, 1, seen, s, v, refs);
			}

			// Path to target head
			List<String> pathFragments = new ArrayList<String>();
			for (Path p : Arrays.asList(
					//new Path(s, head, NodeType.LEMMA, EdgeType.DEP),
					new Path(s, head, NodeType.LEMMA, EdgeType.DIRECTION),
					new Path(s, head, NodeType.POS, EdgeType.DEP),
					new Path(s, head, NodeType.POS, EdgeType.DIRECTION),
					new Path(s, head, NodeType.NONE, EdgeType.DEP),
					new Path(s, head, NodeType.NONE, EdgeType.DIRECTION)
				)) {

				double w = 1d;
				if (p.getNodeType() == NodeType.POS)
					w *= 0.6d;
				if (p.getNodeType() == NodeType.LEMMA)
					w *= 0.3d;

				b(v, refs, w, fs, "path-to-target-head", p.getPath());
				b(v, refs, w, fsc, "path-to-target-head", p.getPath());

				int length = 5;
				//if(p.getNodeType() == NodeType.NONE)
				//	length = 7;
				p.pathNGrams(length, pathFragments, null);
				for (String pf : pathFragments) {
					b(v, refs, w, "path-frag-to-target-head", fs, pf);
					//b(v, refs, w, "path-frag-to-target-head", fsc, pf);
				}
				pathFragments.clear();
			}
		}

		// Pairs of words in sentence
		bag.clear();
		for (int i=0; i<s.size(); i++) {
			String w = s.getWord(i);
			b(v, refs, 0.75d, fs, w, "appears-in-sentence");
			b(v, refs, 0.75d, fsc, w, "appears-in-sentence");
		}
		pairFeatures(fs, 0.75d, bag, v, "-in-sentence", refs);
		pairFeatures(fsc, 0.75d, bag, v, "-in-sentence", refs);

		if (bowWithDirection) {
			// Pairs of words on left
			bag.clear();
			for (int i=0; i<head; i++) {
				String w = s.getWord(i);
				b(v, refs, 0.5d, fs, w, "appears-to-the-left");
				b(v, refs, 0.5d, fsc, w, "appears-to-the-left");
			}
			if (bag.size() == 0) {
				b(v, refs, 0.5d, fs, "nothing-to-the-left");
				b(v, refs, 0.5d, fsc, "nothing-to-the-left");
			} else {
				pairFeatures(fs, 0.5d, bag, v, "-to-the-left", refs);
			}

			// Pairs of words on right
			bag.clear();
			for(int i=head+1; i<s.size(); i++) {
				String w = s.getWord(i);
				b(v, refs, 0.5d, fs, w, "appears-to-the-right");
				b(v, refs, 0.5d, fsc, w, "appears-to-the-right");
			}
			if(bag.size() == 0) {
				b(v, refs, 0.5d, fs, "nothing-to-the-right");
				b(v, refs, 0.5d, fsc, "nothing-to-the-right");
			} else {
				pairFeatures(fs, 0.5d, bag, v, "-to-the-right", refs);
			}
		}

		// Word/pos to the left/right of the extent
		LexicalUnit l = AbstractFeatures.getLUSafe(head-1, s);
		LexicalUnit ll = AbstractFeatures.getLUSafe(head-2, s);
		b(v, refs, fs, "to-the-left=", l.getFullString());
		b(v, refs, fs, "to-the-left=", l.word);
		b(v, refs, fs, "to-the-left=", l.pos);
		b(v, refs, fs, "to-the-left=", ll.word, l.getFullString());
		b(v, refs, fs, "to-the-left=", ll.pos, l.getFullString());
		b(v, refs, fs, "to-the-left=", ll.word, l.word);
		b(v, refs, fs, "to-the-left=", ll.pos, l.word);
		b(v, refs, fs, "to-the-left=", ll.pos, l.pos);
		b(v, refs, fs, "to-the-left=", ll.word, l.pos);
		b(v, refs, fsc, "to-the-left=", l.getFullString());
		b(v, refs, fsc, "to-the-left=", l.word);
		b(v, refs, fsc, "to-the-left=", l.pos);
		b(v, refs, fsc, "to-the-left=", ll.word, l.getFullString());
		b(v, refs, fsc, "to-the-left=", ll.pos, l.getFullString());
		b(v, refs, fsc, "to-the-left=", ll.word, l.word);
		b(v, refs, fsc, "to-the-left=", ll.pos, l.word);
		b(v, refs, fsc, "to-the-left=", ll.pos, l.pos);
		b(v, refs, fsc, "to-the-left=", ll.word, l.pos);

		LexicalUnit r = AbstractFeatures.getLUSafe(head+1, s);
		LexicalUnit rr = AbstractFeatures.getLUSafe(head+2, s);
		b(v, refs, fs, "to-the-right=", r.getFullString());
		b(v, refs, fs, "to-the-right=", r.word);
		b(v, refs, fs, "to-the-right=", r.pos);
		b(v, refs, fs, "to-the-right=", rr.word, r.getFullString());
		b(v, refs, fs, "to-the-right=", rr.pos, r.getFullString());
		b(v, refs, fs, "to-the-right=", rr.word, r.word);
		b(v, refs, fs, "to-the-right=", rr.pos, r.word);
		b(v, refs, fs, "to-the-right=", rr.pos, r.pos);
		b(v, refs, fs, "to-the-right=", rr.word, r.pos);
		b(v, refs, fsc, "to-the-right=", r.getFullString());
		b(v, refs, fsc, "to-the-right=", r.word);
		b(v, refs, fsc, "to-the-right=", r.pos);
		b(v, refs, fsc, "to-the-right=", rr.word, r.getFullString());
		b(v, refs, fsc, "to-the-right=", rr.pos, r.getFullString());
		b(v, refs, fsc, "to-the-right=", rr.word, r.word);
		b(v, refs, fsc, "to-the-right=", rr.pos, r.word);
		b(v, refs, fsc, "to-the-right=", rr.pos, r.pos);
		b(v, refs, fsc, "to-the-right=", rr.word, r.pos);
	}

	private transient IRAMDictionary dict;
	private IRAMDictionary getDict() {
		if(dict == null)
			dict = targetPruningData.getWordnetDict();
		return dict;
	}

	private transient WordnetStemmer stemmer;
	private WordnetStemmer getStemmer() {
		if(stemmer == null)
			stemmer = new WordnetStemmer(getDict());
		return stemmer;
	}

	private static enum ResourceForPos { LU, LEX }

	private void lexicalMatch(
			FeatureVector v,
			LexicalUnit inTarget,
			LexicalUnit inResource,
			ResourceForPos resource,
			Map<String, Double> descriptions,
			boolean allowDifferentPOS,
			Refinements refs) {

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

		// Compute the multiplier for vanilla features
		double mult = 0d;
		boolean posMatch = inTargetTag != null
				&& inTargetTag.equals(inResourceTag);
		boolean exactMatch = posMatch
				&& inTarget.word.equals(inResource.word); 
		if(exactMatch) mult = 1d;
		else if(posMatch) {
			if(inTarget.word.equalsIgnoreCase(inResource.word)) {
				// no case match
				mult = 0.75d;
			}
			else {
				// prefix match
				int m = longestCommonPrefix(
						inTarget.word.toLowerCase(),
						inResource.word.toLowerCase());
				if(m > 2)
					mult = Math.min(0.1d * (m-2), 0.75d);
			}
		}

		// Vanilla match
		for(Map.Entry<String, Double> x : descriptions.entrySet())
			b(v, refs, x.getValue() * mult, resource.toString(), x.getKey());

		// Wordnet match
		boolean wnMadeIt = false;
		boolean tried = false;
		wnMatch:
		if (inTargetTag != null && inResourceTag != null
				&& (allowDifferentPOS || inTargetTag.equals(inResourceTag))) {
			tried = true;
			IRAMDictionary d = getDict();
			WordnetStemmer s = getStemmer();

			List<String> rstems = s.findStems(inResource.word, inResourceTag);
			List<String> tstems = s.findStems(inTarget.word, inTargetTag);
			if (rstems == null || tstems == null
					|| rstems.isEmpty() || tstems.isEmpty()) {
				break wnMatch;
			}

			IIndexWord ti = d.getIndexWord(tstems.get(0), inTargetTag);
			IIndexWord ri = d.getIndexWord(rstems.get(0), inResourceTag);
			if (ti == null || ri == null
					|| ti.getWordIDs().isEmpty() || ri.getWordIDs().isEmpty()) {
				break wnMatch;
			}

			IWordID t = ti.getWordIDs().get(0);
			IWordID r = ri.getWordIDs().get(0);
			IWord tw = d.getWord(t);
			IWord rw = d.getWord(r);
			wnMadeIt = true;

			// Same synset
			ISynset tss = tw.getSynset();
			if (tss.getWords().contains(rw)) {
				for (Map.Entry<String, Double> x : descriptions.entrySet()) {
					b(v, refs, x.getValue(), resource.toString(),
							x.getKey(), "same-synset");
					b(v, refs, x.getValue() / 2d, resource.toString(),
							x.getKey(), "same-synset", inTarget.pos);
				}
			}

			// Related synsets
			Map<IPointer, List<ISynsetID>> relMap =
					tw.getSynset().getRelatedMap();
			for (Entry<IPointer, List<ISynsetID>> rel : relMap.entrySet()) {
				IPointer relation = rel.getKey();
				for (ISynsetID ssid : rel.getValue()) {
					ISynset ss = d.getSynset(ssid);
					boolean fires = ss.getWords().contains(rw);
					if (fires) {
						if(debug) {
							log.info(String.format("%-10s %-15s %-15s holds",
									relation.getName(),
									inTarget.getFullString(),
									inResource.getFullString()));
						}
						for (Map.Entry<String, Double> x :
								descriptions.entrySet()) {
							b(v, refs, x.getValue(), "wordnet",
									resource.toString(), x.getKey(),
									relation.getName());
							b(v, refs, x.getValue() / 2d, "wordnet",
									resource.toString(), x.getKey(),
									relation.getName(), inTarget.pos);
						}
					}
				}
			}
			if (debug && relMap.isEmpty()) {
				log.info("[lexicalMatch] no WordNet relation between "
						+ inTarget.getFullString() + " and "
						+ inResource.getFullString());
			}
		}
		if (debug && !wnMadeIt && tried) {
			log.info("[lexicalMatch] (" + resource + ") failed on WordNet "
					+ "relation between " + inTarget.getFullString()
					+ " and " + inResource.getFullString());
		}
	}

	private int longestCommonPrefix(String a, String b) {
		int n = Math.min(a.length(), b.length());
		int m;
		for(m=0; m < n && a.charAt(m) == b.charAt(m); m++);
		return m;
	}

	@SuppressWarnings("unused")
	private void allChildren(
			String fs,
			int head,
			int depth,
			boolean[] seen,
			Sentence s,
			FeatureVector v,
			Refinements refs) {
		for(int i : s.childrenOf(head)) {
			if(!seen[i]) {
				seen[i] = true;
				LexicalUnit d = s.getLU(i);
				b(v, refs, fs, "descendant=", d.word);
				b(v, refs, fs, "descendant=", d.word,
						"depth=", String.valueOf(depth));
				b(v, refs, fs, "descendant=", d.pos,
						"depth=", String.valueOf(depth));
				allChildren(fs, i, depth+1, seen, s, v, refs);
			}
		}
	}

	private void pairFeatures(
			String fs,
			double weight,
			Set<String> items,
			FeatureVector v,
			String meta,
			Refinements refs) {
		List<String> l = new ArrayList<String>();
		l.addAll(items);
		Collections.sort(l);
		int n = l.size();
		for (int i = 0; i < n - 1; i++) {
			String li = l.get(i);
			for (int j = i + 1; j < n; j++) {
				String lj = l.get(j);
				b(v, refs, weight, fs, li, lj, "appears", meta);
			}
		}
	}

}
