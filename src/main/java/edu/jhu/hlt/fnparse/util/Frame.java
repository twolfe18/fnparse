package edu.jhu.hlt.fnparse.util;

public class Frame {
	private String name;
	private String[] lexicalUnits;
	private String[] roles;
	
	public Frame(String name, String[] lexicalUnits, String[] roles) {
		this.name = name;
		this.lexicalUnits = lexicalUnits;
		this.roles = roles;
	}
	
	public int numLexicalUnits() { return lexicalUnits.length; }
	public int numRoles() { return roles.length; }
	public String getName() { return name; }
	
	public static final Frame NULL_FRAME = new Frame("NOT-A-FRAME", null, null);
}
