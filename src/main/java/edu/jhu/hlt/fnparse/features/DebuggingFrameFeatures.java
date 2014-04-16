package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.util.Alphabet;

public class DebuggingFrameFeatures extends AbstractFeatures<DebuggingFrameFeatures> implements edu.jhu.hlt.fnparse.features.Features.F {

	private static final long serialVersionUID = 1L;

	public DebuggingFrameFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}
	
	@Override
	public void featurize(FeatureVector fv, Refinements r, int targetHeadIdx, Frame f, Sentence sent) {
		
		if(r != Refinements.noRefinements)
			throw new RuntimeException("implement me (in AbstractFeatures.b)!");		

//		if(f == Frame.nullFrame)
//			return emptyFeatures;
		
		b(fv, "frame=", f.getName(), "targetHead=", String.valueOf(targetHeadIdx), "sent=", sent.getId());
		b(fv, "frame=", f.getName());
	}

}
