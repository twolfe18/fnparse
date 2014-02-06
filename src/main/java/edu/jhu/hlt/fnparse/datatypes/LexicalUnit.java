package edu.jhu.hlt.fnparse.datatypes;

import java.util.HashMap;
import java.util.Map;

public class LexicalUnit {

	public final String word;
	public final String pos;
	
	public final int hc;
	
	public LexicalUnit(String word, String pos) {
		if(word == null)
			throw new IllegalArgumentException();
		if(pos == null)
			throw new IllegalArgumentException();
		this.word = word;
		this.pos = pos.toUpperCase();
		this.hc = word.hashCode() ^ pos.hashCode();
	}
	
	/**
	 * compares word by ignoring case, checks for prefix match of POS type
	 * @param fromSentence
	 * @param fromFrameNet should have a POS of "V" not "VBZ"
	 * @return
	 */
	public static boolean approxMatch(LexicalUnit fromSentence, LexicalUnit fromFrameNet) {
		
		String fnPos = frameNetPosToPennPrefixes.get(fromFrameNet.pos);
		if(fnPos == null)
			throw new IllegalArgumentException();
		
		return fromSentence.word.equalsIgnoreCase(fromFrameNet.word)
				&& fromSentence.pos.startsWith(fnPos);
	}
	
	private static Map<String, String> frameNetPosToPennPrefixes;
	static {
		frameNetPosToPennPrefixes = new HashMap<String, String>();
		frameNetPosToPennPrefixes.put("A", "J");	// A=adjective
		frameNetPosToPennPrefixes.put("ADV", "R");
		frameNetPosToPennPrefixes.put("ART", "D");	// D=determiner
		frameNetPosToPennPrefixes.put("C", "CC");
		frameNetPosToPennPrefixes.put("INTJ", "UH");
		frameNetPosToPennPrefixes.put("N", "NN");
		frameNetPosToPennPrefixes.put("NUM", "CD");
		frameNetPosToPennPrefixes.put("PREP", "IN");
		frameNetPosToPennPrefixes.put("SCON", "IN");
		frameNetPosToPennPrefixes.put("V", "V");
	}
	public static Map<String, String> getFrameNetPosToPennPrefixesMap() {
		return frameNetPosToPennPrefixes;
	}
	
	private String fullStr;
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
