package edu.jhu.hlt.fnparse.util;

import edu.jhu.hlt.fnparse.data.*;

public class SemlinkConfiguration extends DefaultConfiguration {
	@Override
	public FrameInstanceProvider getFrameInstanceProvider() {
		return new SemLinkFrameInstanceProvider();
	}
}
