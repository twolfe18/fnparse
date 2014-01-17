package edu.jhu.hlt.fnparse.datatypes;

public class Sentence {

	private String id;
	private String[] tokens;
	private String[] pos;
	
	public Sentence(String id, String[] tokens, String[] pos) {
		this.id = id;
		this.tokens = tokens;
		this.pos = pos;
	}
	
	public String getId() { return id; }
	public String getWord(int i) { return tokens[i]; }
	public String getPos(int i) { return pos[i]; }
	public String[] getWord() { return tokens; }
	public String[] getPos() { return pos; }
	
	public int getHead(Span s) {
		System.err.println("warning: not actually doing head-finding, update me");
		return s.end-1;
	}
	
	public int size() { return tokens.length; }
}
