package edu.jhu.hlt.fnparse.features;

import edu.jhu.hlt.fnparse.util.Frame;
import edu.jhu.hlt.fnparse.util.Sentence;
import travis.Vector;

public interface TargetFeature {

	public String getName();
	
	public Vector getFeatures(Frame f, int targetIdx, Sentence s);
}

