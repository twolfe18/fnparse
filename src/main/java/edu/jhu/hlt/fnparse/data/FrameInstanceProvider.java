package edu.jhu.hlt.fnparse.data;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FrameInstance;

public interface FrameInstanceProvider {
	
	public String getName();
	
	public List<FrameInstance> getFrameInstances();
	
}
