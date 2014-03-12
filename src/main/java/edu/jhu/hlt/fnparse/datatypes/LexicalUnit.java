package edu.jhu.hlt.fnparse.datatypes;


public class LexicalUnit {

	public final String word;
	public final String pos;
	private transient String fullStr;
	public final int hc;
	
	public LexicalUnit(String word, String pos) {
		if(word == null)
			throw new IllegalArgumentException();
		if(pos == null)
			throw new IllegalArgumentException();
		this.word = word;
		this.pos = pos.toUpperCase();
		this.hc = this.word.hashCode() ^ this.pos.hashCode();
	}
	
	public String getFullString() {
		if(fullStr == null)
			fullStr = word + "." + pos;
		return fullStr;
	}
	
	public String toString() { return String.format("<LU %s.%s>", word, pos); }
	
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
