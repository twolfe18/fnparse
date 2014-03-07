package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.util.Alphabet;

public class DebuggingFrameFeatures extends AbstractFeatures<DebuggingFrameFeatures> implements edu.jhu.hlt.fnparse.features.Features.F {

	public DebuggingFrameFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, int targetHeadIdx, Sentence sent) {
		
//		if(f == Frame.nullFrame)
//			return emptyFeatures;
		
		FeatureVector fv = new FeatureVector();
		b(fv, "frame=", f.getName(), "targetHead=", String.valueOf(targetHeadIdx), "sent=", sent.getId());
		b(fv, "frame=", f.getName());
		return fv;
	}

}
