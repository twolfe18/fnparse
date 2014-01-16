package edu.jhu.hlt.fnparse.util;

import java.util.List;
import java.util.HashMap;
import java.util.Vector;

import edu.jhu.hlt.fnparse.data.UsefulConstants;
import edu.jhu.hlt.fnparse.data.DataUtil;

public class Frame {

	private int idx;
	private String name;
	private String[] lexicalUnit;
	private String[] role;

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

	/**
	 * Frame used to indicate that a word does not evoke a frame
	 */
	public static final Frame NULL_FRAME = new Frame(0, "NOT-A-FRAME", null, null);

	/**
	 * get a frame by its id (constant time)
	 */
	public static Frame getFrame(int id) {
		throw new RuntimeException("implement me");
	}
	
	/**
	 * includes NULL_FRAME
	 */
	public static List<Frame> allFrames() {
		List<Frame> frameList = new Vector<Frame>();
		try{
			HashMap tmp = DataUtil.parseFrameIndexXML(UsefulConstants.frameIndexXMLPath, UsefulConstants.framesInFrameNet);
			int id[] = (int[]) tmp.get("id");
			String name[] = (String []) tmp.get("name");	    
			for(int i=0; i < UsefulConstants.framesInFrameNet; i++){
				HashMap tmp2 = DataUtil.lexicalUnitAndRolesOfFrame(name[i]);
				String lexicalUnit[] = (String []) tmp2.get("lexicalUnit");
				String role[] = (String []) tmp2.get("role");	    
				frameList.add(new Frame(id[i], name[i], lexicalUnit, role));
			}
			frameList.add(NULL_FRAME);
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
		return frameList;
	}
}
