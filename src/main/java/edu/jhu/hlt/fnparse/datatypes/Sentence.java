package edu.jhu.hlt.fnparse.datatypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.fnparse.util.HasId;

public class Sentence implements HasId {
	
	public static final Sentence nullSentence =
			new Sentence("nullSentenceDataset", "nullSentence", new String[0], new String[0], new int[0], new String[0]);

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
	
	private int[] gov;			// values are 0-indexed, root is -1
	private String[] depType;
	
	public Sentence(String dataset, String id, String[] tokens, String[] pos, int[] gov, String[] depType) {
		if(id == null || tokens == null)
			throw new IllegalArgumentException();
		if(pos != null && tokens.length != pos.length)
			throw new IllegalArgumentException();
		this.dataset = dataset;
		this.id = id;
		this.tokens = tokens;
		this.pos = pos;
		this.gov=gov;
		this.depType=depType;
		
		// upcase the POS tags for consistency (e.g. with LexicalUnit)
		for(int i=0; i<pos.length; i++)
			this.pos[i] = this.pos[i].toUpperCase();
	}

	public Sentence copy() {
		return new Sentence(dataset, id, tokens.clone(), pos.clone(), gov.clone(), depType.clone());
	}

	public String getDataset() { return dataset; }
	public String getId() { return id; }
	
	public LexicalUnit getLU(int i) { return new LexicalUnit(tokens[i], pos[i]); }
	public String[] getWords() {return Arrays.copyOf(tokens, tokens.length);}
	public String getWord(int i) { return tokens[i]; }
	public String getPos(int i) { return pos[i]; }
	public String[] getWordFor(Span s) { return Arrays.copyOfRange(tokens, s.start, s.end); }
	public String[] getPosFor(Span s) { return Arrays.copyOfRange(pos, s.start, s.end); }
	
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
		for(int i=0; i<size(); i++)
			sb.append(String.format(" %s/%s", getWord(i), getPos(i)));
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
