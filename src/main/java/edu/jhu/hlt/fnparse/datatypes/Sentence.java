package edu.jhu.hlt.fnparse.datatypes;

import java.util.*;

import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.util.HasId;
import edu.mit.jwi.IDictionary;
import edu.mit.jwi.morph.WordnetStemmer;

public final class Sentence implements HasId {
	
	public static final Sentence nullSentence =
			new Sentence("nullSentenceDataset", "nullSentence", new String[0], new String[0], new String[0], new int[0], new String[0]);

	/**
	 * a __globally__ unique identifier
	 * should not overlap between datasets
	 */
	private String id;

	/**
	 * where did this sentence come from?
	 * redundant with id, a convenience
	 */
	private String dataset;
	
	private String[] tokens;
	private String[] pos;
	private String[] lemmas;
	
	private int[] gov;			// values are 0-indexed, root is -1
	private String[] depType;
	
	public Sentence(String dataset, String id, String[] tokens, String[] pos, String[] lemmas, int[] gov, String[] depType) {
		if(id == null || tokens == null)
			throw new IllegalArgumentException();
		
		final int n = tokens.length;
		if(pos != null && pos.length != n)
			throw new IllegalArgumentException();
		if(lemmas != null && lemmas.length != n)
			throw new IllegalArgumentException();
		if(gov != null && gov.length != n)
			throw new IllegalArgumentException();
		if(depType != null && depType.length != n)
			throw new IllegalArgumentException();
		
		this.dataset = dataset;
		this.id = id;
		this.tokens = tokens;
		this.pos = pos;
		this.lemmas = lemmas;
		this.gov = gov;
		this.depType = depType;
		
		// upcase the POS tags for consistency (e.g. with LexicalUnit)
		for(int i=0; i<pos.length; i++)
			this.pos[i] = this.pos[i].toUpperCase().intern();
	}

	public Sentence copy() {
		return new Sentence(dataset, id, tokens.clone(), pos.clone(), lemmas.clone(), gov.clone(), depType.clone());
	}

	public String getDataset() { return dataset; }
	public String getId() { return id; }
	
	
	public static final String fnStyleBadPOSstrPrefix = "couldn't convert Penn tag of ".toUpperCase();	// once in LU, this will be upcased anyway
	/**
	 * uses the lemma instead of the word, and converts POS to
	 * a FrameNet style POS.
	 */
	public LexicalUnit getFNStyleLU(int i, IDictionary dict) {
		LexicalUnit x = getFNStyleLUUnsafe(i, dict);
		if(x == null)
			return new LexicalUnit(tokens[i], fnStyleBadPOSstrPrefix + pos[i]);
		else
			return x;
	}
	
	public LexicalUnit getFNStyleLUUnsafe(int i, IDictionary dict) {
		String fnTag = PosUtil.getPennToFrameNetTags().get(pos[i]);
		if(fnTag == null) return null;
		WordnetStemmer stemmer = new WordnetStemmer(dict);
		List<String> allStems = Collections.emptyList();
		try {
			allStems = stemmer.findStems(tokens[i], PosUtil.ptb2wordNet(pos[i]));
		}
		catch(java.lang.IllegalArgumentException e) {
			//throw new RuntimeException("bad word? " + getLU(i), e);
			System.err.println("bad word? " + getLU(i));
		}
		if(allStems.isEmpty()) return null;
		return new LexicalUnit(allStems.get(0), fnTag);
	}
	
	/**
	 * gives you a LexicalUnit using the lemma for this token rather than the actual word.
	 * this method is safe (if you call with i=-1 or i=n, it will return "<S>" and "</S>" fields).
	 */
	public LexicalUnit getLemmaLU(int i) {
		if(i == -1)
			return AbstractFeatures.luStart;
		if(i == tokens.length)
			return AbstractFeatures.luEnd;
		return new LexicalUnit(lemmas[i], pos[i]);
	}
	
	/**
	 * gives you the original word (no case change or anything),
	 * along with the POS tag (your only guarantee is that this is upper case).
	 */
	public LexicalUnit getLU(int i) { return new LexicalUnit(tokens[i], pos[i]); }
	
	public String[] getWords() {return Arrays.copyOf(tokens, tokens.length);}
	public String getWord(int i) { return tokens[i]; }
	public String[] getPos() { return pos; }
	public String getPos(int i) { return pos[i]; }
	public String[] getLemmas() { return lemmas; }
	public String getLemma(int i){return lemmas[i];}
	
	public String[] getWordFor(Span s) { return Arrays.copyOfRange(tokens, s.start, s.end); }
	public String[] getPosFor(Span s) { return Arrays.copyOfRange(pos, s.start, s.end); }
	public String[] getLemmasFor(Span s) { return Arrays.copyOfRange(lemmas, s.start, s.end); }
	
	private transient int[][] children;	// opposite of gov
	public int[] childrenOf(int wordIdx) {
		if(children == null) {
			int n = gov.length;
			children = new int[n][];
			List<Integer> kids = new ArrayList<Integer>();
			for(int i=0; i<n; i++) {
				kids.clear();
				for(int j=0; j<n; j++)
					if(gov[j] == i) kids.add(j);
				children[i] = new int[kids.size()];
				for(int j=0; j<kids.size(); j++)
					children[i][j] = kids.get(j);
			}
		}
		return children[wordIdx];
	}
	
	
	public int governor(int i) {
		return gov[i];
	}
	
	public String dependencyType(int childIdx) {
		return depType[childIdx];
	}
	
	public List<String> wordsIn(Span s) {
		List<String> l = new ArrayList<String>();
		for(int i=s.start; i<s.end; i++)
			l.add(tokens[i]);
		return l;
	}
	
	public List<String> posIn(Span s) {
		List<String> l = new ArrayList<String>();
		for(int i=s.start; i<s.end; i++)
			l.add(pos[i]);
		return l;
	}
	
	public int size() { return tokens.length; }
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("<Sentence");
		sb.append(" ");
		sb.append(id);
		for(int i=0; i<size(); i++) {
			//sb.append(String.format(" %s/%s", getWord(i), getPos(i)));
			sb.append(String.format(" %s", getWord(i)));
		}
		sb.append(">");
		return sb.toString();
	}
	
	@Override
	public int hashCode() { return id.hashCode(); }
	
	@Override
	public boolean equals(Object o) {
		if(o instanceof Sentence) {
			Sentence other = (Sentence) o;
			return id.equals(other.id) && Arrays.equals(tokens, other.tokens)
					&& Arrays.equals(pos, other.pos) && Arrays.equals(gov, other.gov)
					&& Arrays.equals(depType, other.depType);
		}
		else return false;
	}
}
