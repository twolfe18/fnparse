package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.util.Alphabet;

public class DebuggingFrameRoleFeatures extends AbstractFeatures<DebuggingFrameRoleFeatures> implements Features.FR {
	
	public DebuggingFrameRoleFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, boolean argIsRealized, int targetHeadIdx, int roleIdx, int argHead, Sentence sent) {

		if(argIsRealized && roleIdx >= f.numRoles())
			throw new IllegalArgumentException();

		FeatureVector fv = new FeatureVector();
		String t = sent.getLU(targetHeadIdx).getFullString();
		String a = argIsRealized ? "null" : sent.getLU(argHead).getFullString();
		b(fv, "frame=", f.getName(), "roleIdx=", String.valueOf(roleIdx), "target=", t, "arg=", a);
		return fv;
	}
}

