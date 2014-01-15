package edu.jhu.hlt.fnparse.util;

import java.util.List;

public class Frame {
	
	private int idx;
	private String name;
	private String[] lexicalUnits;
	private String[] roles;
	
	public Frame(int id, String name, String[] lexicalUnits, String[] roles) {
		this.idx = id;
		this.name = name;
		this.lexicalUnits = lexicalUnits;
		this.roles = roles;
	}
	
	public int getId() { return idx; }
	public String getLexicalUnit(int i) { return lexicalUnits[i]; }
	public int numLexicalUnits() { return lexicalUnits.length; }
	public String getRow(int i) { return roles[i]; }
	public int numRoles() { return roles.length; }
	public String getName() { return name; }
	
	/**
	 * Frame used to indicate that a word does not evoke a frame
	 */
	public static final Frame NULL_FRAME = new Frame(0, "NOT-A-FRAME", null, null);
	
	/**
	 * includes NULL_FRAME
	 */
	public static List<Frame> allFrames() {
		throw new RuntimeException("implement me");
	}
	
}
