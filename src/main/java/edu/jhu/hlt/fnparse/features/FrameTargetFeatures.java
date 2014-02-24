package edu.jhu.hlt.fnparse.features;

import java.util.Arrays;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.util.Alphabet;

public class FrameTargetFeatures extends AbstractFeatures<FrameTargetFeatures> implements Features.FE {

	private final FeatureVector nullFrameFeatures;
	private final int nullFrameFeatureIdx;
	
	public FrameTargetFeatures(Alphabet<String> featIdx) {
		super(featIdx);
		nullFrameFeatureIdx = featIdx.lookupIndex(getName() + "_nullFrame", true);
		nullFrameFeatures = new FeatureVector();
		nullFrameFeatures.add(nullFrameFeatureIdx, 1d);
	}
	
	@Override
	public List<Integer> dontRegularize() {
		return Arrays.asList(nullFrameFeatureIdx);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, Span trigger, Sentence s) {
		
		// nullFrame feature seems to be out-weighing the overfitting feature (i.e. f=self_motion, trigger=x)
		// when you add a backoff feature (i.e. frame=self_motion), it wins out over just the nullFrame feature
		// this is worrying though, it seems to indicate that the combination of number of backoff features,
		// combined with the regularizer, makes the model biased towards certain outcomes.
		
		// would removing the nullFrame feature from the regularizer fix the problem?
		// i think it would at least help.
		// in principle, we really don't need to regularize this one feature, we have plenty
		// of data to fit it, and the bias incurred by regularizing it can be dangerous.
		
		if(f == Frame.nullFrame)
			return nullFrameFeatures;
		
		FeatureVector fv = new FeatureVector();
		
//		String fs = "frame=" + f.getId();
//		String ws = "width=" + trigger.width();
//		
//		// width
//		b(fv, ws);
//		b(fv, fs + "_" + ws);
//		
//		// position
//		border(fv, "fromLeft", trigger.start, s);
//		border(fv, fs + "_fromLeft", trigger.start, s);
//		border(fv, ws + "_fromLeft", trigger.start, s);
//		border(fv, fs + "_" + ws + "_fromLeft", trigger.start, s);
//		
//		border(fv, "fromRight", s.size()-trigger.end, s);
//		border(fv, fs + "_fromRight", s.size()-trigger.end, s);
//		border(fv, ws + "_fromRight", s.size()-trigger.end, s);
//		border(fv, fs + "_" + ws + "_fromRight", s.size()-trigger.end, s);
		
		// TODO more lexical stuff
		
		// DEBUGGING
		b(fv, "frame=" + f.getName() + "_trigger=" + trigger + "_sent=" + s.getId());
		b(fv, "frame=" + f.getName() + "_sent=" + s.getId());
		
		return fv;
	}
	
	public void border(FeatureVector fv, String desc, int border, Sentence s) {
		b(fv, desc + "=" + border);
		b(fv, desc + "/2=" + (border/2));
		b(fv, desc + "/4=" + (border/4));
		int n = s.size();
		b(fv, desc + "*2/n=" + ((border*2)/n));
		b(fv, desc + "*4/n=" + ((border*4)/n));
		b(fv, desc + "*8/n=" + ((border*8)/n));
	}

}
