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
	public void featurize(FeatureVector v, Refinements r, int targetHeadIdx, Frame f, int argHead, int roleIdx, Sentence sent) {

		if(r != Refinements.noRefinements)
			throw new RuntimeException("implement me (in AbstractFeatures.b)!");		

		if(roleIdx >= f.numRoles())
			throw new IllegalArgumentException();

		FeatureVector fv = new FeatureVector();
		String t = sent.getLU(targetHeadIdx).getFullString();
		String a = argHead < sent.size() ? sent.getLU(argHead).getFullString() : "null";
		b(fv, "frame=", f.getName(), "roleIdx=", String.valueOf(roleIdx), "target=", t, "arg=", a);
	}

}

