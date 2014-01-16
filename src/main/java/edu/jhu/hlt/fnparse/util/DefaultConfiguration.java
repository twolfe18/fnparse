package edu.jhu.hlt.fnparse.util;

import edu.jhu.hlt.fnparse.data.FNFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;

public class DefaultConfiguration implements Configuration {

	private FrameIndex frameIndex;
	private FrameInstanceProvider frameInstProvider;
	
	@Override
	public FrameIndex getFrameIndex() {
		if(frameIndex == null)
			frameIndex = new FrameIndex();
		return frameIndex;
	}

	@Override
	public FrameInstanceProvider getFrameInstanceProvider() {
		if(frameInstProvider == null)
			frameInstProvider = new FNFrameInstanceProvider();
		return frameInstProvider;
	}

}
