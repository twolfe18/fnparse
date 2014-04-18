package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.util.Alphabet;

public class DebuggingRoleFeatures extends AbstractFeatures<DebuggingRoleFeatures> implements Features.R {

	private static final long serialVersionUID = 1L;

	public DebuggingRoleFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}
	
	@Override
	public void featurize(FeatureVector v, Refinements refs, int targetHeadIdx, Frame f, int argHead, int roleIdx, Sentence sent) {

		if(roleIdx >= f.numRoles())
			throw new IllegalArgumentException();

		String fs = "frame=" + f.getName();
		String t = "target=" + sent.getLU(targetHeadIdx).getFullString();
		String a = "arg=" + (argHead < sent.size() ? sent.getLU(argHead).getFullString() : "null");
		String r = "roleIdx=" + f.getRole(roleIdx);
		b(v, refs, fs, r, t, a);
	}

}

