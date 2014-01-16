package edu.jhu.hlt.fnparse.util;

public class DefaultConfiguration implements Configuration {

	private FrameIndex frameIndex;
	
	@Override
	public FrameIndex getFrameIndex() {
		if(frameIndex == null)
			frameIndex = new FrameIndex();
		return frameIndex;
	}

}
