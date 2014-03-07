package edu.jhu.hlt.fnparse.datatypes;

public class Frame {

	private int idx;
	private String name;				// e.g. "Commerce_buy"
	private LexicalUnit[] lexicalUnits;	// e.g. ["purchase.v", "buy.v"]
	private String[] roles;				// e.g. ["Buyer", "Goods"]

	public Frame(int id, String name, LexicalUnit[] lexicalUnits, String[] roles) {
		if(roles == null || roles.length == 0)
			throw new IllegalArgumentException();
		if(lexicalUnits == null)
			throw new IllegalArgumentException(name + " has no LUs");
		this.idx = id;
		this.name = name;
		this.lexicalUnits = lexicalUnits;
		this.roles = roles;
	}
	
	private Frame() {
		this.idx = 0;
		this.name = "NOT_A_FRAME";
		this.lexicalUnits = null;
		this.roles = null;
	}
	
	public String toString() {
		return String.format("<Frame %d %s has %d roles>", idx, name, numRoles());
	}

	public int getId() { return idx; }
	
	public LexicalUnit getLexicalUnit(int i) {
		return lexicalUnits[i];
	}
	
	public int numLexicalUnits() {
		if(this == nullFrame)
			return 0;
		return lexicalUnits.length;
	}
	
	public String getRole(int i) {
		return roles[i];
	}
	
	public String getRoleSafe(int i) {
		if(i < roles.length)
			return roles[i];
		return "<not-a-role>";
	}
	
	public int numRoles() {
		if(this == nullFrame)
			return 0;
		return roles.length;
	}
	
	public String[] getRoles() { return roles; }
	
	public String getName() { return name; }
	
	@Override
	public int hashCode() { return idx; }
	
	@Override
	public boolean equals(Object other) {
		if(other instanceof Frame) {
			Frame f = (Frame) other;
			return idx == f.idx;
		}
		else return false;
	}
	
	/**
	 * Frame used to indicate that a word does not evoke a frame
	 */
	public static final Frame nullFrame = new Frame();
}
