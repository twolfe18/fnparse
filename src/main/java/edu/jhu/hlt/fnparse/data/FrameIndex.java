package edu.jhu.hlt.fnparse.data;

import java.util.*;

import edu.jhu.hlt.fnparse.datatypes.Frame;

/**
 * Reads frames from disk and provides access to them
 */
public class FrameIndex {

	public final int framesInFrameNet = 1019;	// The number of frames in Framenet 1.5
	
	private List<Frame> allFrames;
	
	/**
	 * Frame used to indicate that a word does not evoke a frame
	 */
	public final Frame nullFrame = new Frame(0, "NOT-A-FRAME", null, null);

	
	/**
	 * get a frame by its id (constant time)
	 */
	public Frame getFrame(int id) {
		throw new RuntimeException("implement me");
	}
	
	/**
	 * includes NULL_FRAME
	 */
	public List<Frame> allFrames() {
		if(allFrames == null) {
			allFrames = new ArrayList<Frame>();
			allFrames.add(nullFrame);
			String name[] = DataUtil.parseFrameIndexXML(UsefulConstants.frameIndexXMLPath, framesInFrameNet);
			for(int i=0; i<name.length; i++) {
				HashMap<String, String[]> tmp2 = DataUtil.lexicalUnitAndRolesOfFrame(name[i]);
				String lexicalUnit[] = (String []) tmp2.get("lexicalUnit");
				String role[] = (String []) tmp2.get("role");	    
				allFrames.add(new Frame(i+1, name[i], lexicalUnit, role));
			}
		}
		return allFrames;
	}
}
