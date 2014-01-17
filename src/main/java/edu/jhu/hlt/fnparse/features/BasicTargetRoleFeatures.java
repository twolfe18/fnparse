package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.util.Alphabet;

public class BasicTargetRoleFeatures implements TargetRoleFeatures {

	private Alphabet<String> featIdx = new Alphabet<String>();
	private Frame nullFrame;
	
	public BasicTargetRoleFeatures(Frame nullFrame) {
		this.nullFrame = nullFrame;
	}
	
	@Override
	public String getDescription() { return "BasicTargetRoleFeatures"; }

	@Override
	public String getFeatureName(int featIdx) {
		return this.featIdx.lookupObject(featIdx);
	}

	@Override
	public FeatureVector getFeatures(Frame f, Span span, int targetIdx, int roleIdx, Sentence sent) {
		
		// NOTE: don't write any back-off features that only look at roleIdx because it is
		// meaningless outside without considering the frame.
		
		FeatureVector fv = new FeatureVector();
		
		fv.add(index("widthBias" + span.width()), 1d);
		if(span == Span.nullSpan && f == nullFrame)
			fv.add(index("nullFrame_and_nullSpan"), 1d);
		
		String role_width = String.format("frame%d_role%d_width%d", f.getId(), roleIdx, span.width());
		fv.add(index(role_width), 1d);
		
		String rel = "wut";
		if(span.start > targetIdx) rel = "right";
		if(span.end <= targetIdx) rel = "left";
		if(targetIdx >= span.start && targetIdx < span.end) rel = "cover";
		String role_head = String.format("frame%d_role%d_", f.getId(), roleIdx, rel);
		fv.add(index(role_head), 1d);
		
		return fv;
	}
	
	private int index(String featureName) {
		return featIdx.lookupIndex(featureName, true);
	}

	@Override
	public int cardinality() {
		// TODO do some real counting
		int s = 5000;
		assert featIdx.size() < s;
		return s;
	}

}
