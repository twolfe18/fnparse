package edu.jhu.hlt.fnparse.util;

public class Sentence {

	private String id;
	private String[] tokens;
	private String[] pos;
	
	public String getId() { return id; }
	public String getWord(int i) { return tokens[i]; }
	public String getPos(int i) { return pos[i]; }
	public int size() { return tokens.length; }
}
