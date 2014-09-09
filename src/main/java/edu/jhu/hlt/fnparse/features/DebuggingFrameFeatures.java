package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.HasParserParams;

public class DebuggingFrameFeatures extends AbstractFeatures<DebuggingFrameFeatures> implements edu.jhu.hlt.fnparse.features.Features.F {

	private static final long serialVersionUID = 1L;

	public DebuggingFrameFeatures(HasParserParams globalParams) {
		super(globalParams);
	}
	
	@Override
	public void featurize(FeatureVector fv, Refinements r, int targetHeadIdx, Frame f, Sentence sent) {
		
//		if(f == Frame.nullFrame)
//			return emptyFeatures;
		
		b(fv, r, "frame=", f.getName(), "targetHead=", String.valueOf(targetHeadIdx), "sent=", sent.getId());
		b(fv, r, "frame=", f.getName());
	}

}
