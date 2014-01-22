package edu.jhu.hlt.fnparse.data;

public class LexicalUnit {

	public final String word;
	public final String pos;
	
	public final int hc;
	
	public LexicalUnit(String word, String pos) {
		if(word == null)
			throw new IllegalArgumentException();
		if(pos == null)
			throw new IllegalArgumentException();
		this.word = word.toLowerCase();
		this.pos = pos.toUpperCase();
		this.hc = word.hashCode() ^ pos.hashCode();
	}
	
	@Override
	public int hashCode() { return hc; }
	
	@Override
	public boolean equals(Object other) {
		if(other instanceof LexicalUnit) {
			LexicalUnit olu = (LexicalUnit) other;
			return word.equals(olu.word) && pos.equals(olu.pos);
		}
		else return false;
	}
}
