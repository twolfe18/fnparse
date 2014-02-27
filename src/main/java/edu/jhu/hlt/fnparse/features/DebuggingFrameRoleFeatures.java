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
		String arg = "null";
		if(argIsRealized) {
			StringBuilder sb = new StringBuilder();
			for(int i=argument.start; i<argument.end; i++)
				sb.append("-" + sent.getWord(i));
			arg = sb.toString();
		}
		b(fv, "frame=" + f.getName() + "_roleIdx=" + f.getRoles()[roleIdx] +
				"_targetHead=" + sent.getLU(targetHeadIdx) + "_argument=" + arg);
		return fv;
	}
}

/*
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
		b(fv, "frame=" + f.getName() + "_roleIdx=" + roleIdx + "_target=" + t + "_arg=" + a);
		return fv;
	}
}
*/

