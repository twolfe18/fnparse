package edu.jhu.hlt.fnparse.datatypes;

import java.util.List;
import java.util.Vector;
import java.util.HashMap;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.UsefulConstants;

public class Frame {

	private int idx;
	private String name;			// e.g. "Commerce_buy"
	private String[] lexicalUnit;	// e.g. ["purchase.v", "buy.v"]
	private String[] role;			// e.g. ["Buyer", "Goods"]

	public Frame(int id, String name, String[] lexicalUnit, String[] role) {
		this.idx = id;
		this.name = name;
		this.lexicalUnit = lexicalUnit;
		this.role = role;
	}
	
	public String toString() {
		return String.format("<Frame %d %s>", idx, name);
	}

	public int getId() { return idx; }
	
	public String getLexicalUnit(int i) { return lexicalUnit[i]; }
	public int numLexicalUnits() { return lexicalUnit.length; }
	
	public String getRow(int i) { return role[i]; }
	public int numRoles() { return role.length; }
	
	public String getName() { return name; }
}
