package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.util.Alphabet;

public class DebuggingFrameRoleFeatures extends AbstractFeatures<DebuggingFrameRoleFeatures> implements Features.FR {

	private static final long serialVersionUID = 1L;

	public DebuggingFrameRoleFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, int targetHeadIdx, int roleIdx, int argHead, Sentence sent) {

		if(roleIdx >= f.numRoles())
			throw new IllegalArgumentException();

		FeatureVector fv = new FeatureVector();
		String t = sent.getLU(targetHeadIdx).getFullString();
		String a = sent.getLU(argHead).getFullString();
		b(fv, "frame=", f.getName(), "roleIdx=", String.valueOf(roleIdx), "target=", t, "arg=", a);
		return fv;
	}
}

