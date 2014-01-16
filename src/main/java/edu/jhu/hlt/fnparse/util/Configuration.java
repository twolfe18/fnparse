package edu.jhu.hlt.fnparse.util;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;

/**
 * Represents a configuration for anything that might need to be a global variable.
 * @author travis
 */
public interface Configuration {
	
	public FrameIndex getFrameIndex();
	
	public FrameInstanceProvider getFrameInstanceProvider();
	
}
