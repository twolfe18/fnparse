package edu.jhu.hlt.fnparse.datatypes;

public class Frame {

	private int idx;
	private String name;			// e.g. "Commerce_buy"
	private String[] lexicalUnit;	// e.g. ["purchase.v", "buy.v"]
	private String[] role;			// e.g. ["Buyer", "Goods"]

	public Frame(int id, String name, String[] lexicalUnit, String[] role) {
		if(role == null || role.length == 0)
			throw new IllegalArgumentException();
		if(lexicalUnit == null || lexicalUnit.length == 0)
			throw new IllegalArgumentException();
		this.idx = id;
		this.name = name;
		this.lexicalUnit = lexicalUnit;
		this.role = role;
	}
	
	private Frame() {
		this.idx = 0;
		this.name = "NOT_A_FRAME";
		this.lexicalUnit = null;
		this.role = null;
	}
	
	public String toString() {
		return String.format("<Frame %d %s>", idx, name);
	}

	public int getId() { return idx; }
	
	public String getLexicalUnit(int i) {
		return lexicalUnit[i];
	}
	
	public int numLexicalUnits() {
		if(this == nullFrame)
			return 0;
		return lexicalUnit.length;
	}
	
	public String getRow(int i) {
		return role[i];
	}
	
	public int numRoles() {
		if(this == nullFrame)
			return 0;
		return role.length;
	}
	
	public String getName() { return name; }
	
	/**
	 * Frame used to indicate that a word does not evoke a frame
	 */
	public static final Frame nullFrame = new Frame();
}
