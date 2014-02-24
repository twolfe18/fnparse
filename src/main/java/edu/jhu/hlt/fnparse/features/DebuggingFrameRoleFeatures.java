package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.util.Alphabet;

public class DebuggingFrameRoleFeatures extends AbstractFeatures<DebuggingFrameRoleFeatures> implements Features.FRE {

	public DebuggingFrameRoleFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, boolean argIsRealized, int targetHeadIdx, int roleIdx, Span argument, Sentence sent) {
		
		if(argIsRealized && roleIdx >= f.numRoles())
			throw new IllegalArgumentException();

		// only include enough features to overfit the one training data
		FeatureVector fv = new FeatureVector();
		String arg = argIsRealized ? "null" : argument.toString();
		b(fv, "frame=" + f.getName() + "_roleIdx=" + roleIdx +
				"_targetHead=" + targetHeadIdx + "_argumentSpan=" + arg + "_sent=" + sent.getId());
		return fv;
	}

}
