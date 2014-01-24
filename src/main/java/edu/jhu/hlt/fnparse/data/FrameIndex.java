package edu.jhu.hlt.fnparse.data;

import java.util.*;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;

/**
 * Reads frames from disk and provides access to them
 */
public class FrameIndex {

	public final int framesInFrameNet = 1019;	// The number of frames in Framenet 1.5
	
	private List<Frame> allFrames;
	
	/**
	 * Frame used to indicate that a word does not evoke a frame
	 */
	public final Frame nullFrame = Frame.nullFrame;
	
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
				HashMap<String, Object> tmp2 = DataUtil.lexicalUnitAndRolesOfFrame(name[i]);
				//System.out.println("frame = " + name[i] + ", tmp2 = " + tmp2);
				LexicalUnit[] lexicalUnits = (LexicalUnit[]) tmp2.get("lexicalUnits");
				String[] roles = (String[]) tmp2.get("roles");	    
				allFrames.add(new Frame(i+1, name[i], lexicalUnits, roles));
			}
		}
		return allFrames;
	}
}
