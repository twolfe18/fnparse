package edu.jhu.hlt.fnparse.util;

import java.util.*;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.UsefulConstants;

/**
 * Reads frames from disk and provides access to them
 */
public class FrameIndex {

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
			try{
				HashMap tmp = DataUtil.parseFrameIndexXML(UsefulConstants.frameIndexXMLPath, UsefulConstants.framesInFrameNet);
				int id[] = (int[]) tmp.get("id");
				String name[] = (String []) tmp.get("name");	    
				for(int i=0; i < UsefulConstants.framesInFrameNet; i++){
					HashMap tmp2 = DataUtil.lexicalUnitAndRolesOfFrame(name[i]);
					String lexicalUnit[] = (String []) tmp2.get("lexicalUnit");
					String role[] = (String []) tmp2.get("role");	    
					allFrames.add(new Frame(id[i], name[i], lexicalUnit, role));
				}
				allFrames.add(nullFrame);
			}
			catch(Exception e) {
				throw new RuntimeException(e);
			}
		}
		return allFrames;
	}
}
