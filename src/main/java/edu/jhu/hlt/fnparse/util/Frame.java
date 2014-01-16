package edu.jhu.hlt.fnparse.util;

import java.util.List;
import java.util.Vector;
import java.util.HashMap;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.UsefulConstants;
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
	 * includes NULL_FRAME
	 */
	public static List<Frame> allFrames() {
		List<Frame> frameList = new Vector<Frame>();
		try{
			String name[] = DataUtil.parseFrameIndexXML(UsefulConstants.frameIndexXMLPath, UsefulConstants.framesInFrameNet);
			frameList.add(NULL_FRAME);
			for(int i=1; i <= UsefulConstants.framesInFrameNet; i++){
				HashMap<String, String[]> tmp2 = DataUtil.lexicalUnitAndRolesOfFrame(name[i]);
				String lexicalUnit[] = (String []) tmp2.get("lexicalUnit");
				String role[] = (String []) tmp2.get("role");	    
				frameList.add(new Frame(i, name[i], lexicalUnit, role));
			}
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
		return frameList;
	}
}
