package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.util.Alphabet;

public class BasicFrameElemFeatures implements FrameElementFeatures {

	private Alphabet<String> featIdx = new Alphabet<String>();
	public boolean verbose = false;
	
	@Override
	public String getDescription() { return "BasicTargetRoleFeatures"; }

	@Override
	public String getFeatureName(int featIdx) {
		return this.featIdx.lookupObject(featIdx);
	}
	
	public int head(Span s) {
		if(s.width() == 1)
			return s.start;
		else {
			System.err.println("warning! implement a real head finder");
			return s.end-1;
		}
	}

	@Override
	public FeatureVector getFeatures(Frame f, Span argumentSpan, Span targetSpan, int roleIdx, Sentence sent) {
		
		// NOTE: don't write any back-off features that only look at roleIdx because it is
		// meaningless outside without considering the frame.
		
		FeatureVector fv = new FeatureVector();
		
		fv.add(index("widthBias" + argumentSpan.width()), 1d);
		if(argumentSpan == Span.nullSpan && f == Frame.nullFrame)
			fv.add(index("nullFrame_and_nullSpan"), 1d);
		
		String role_width = String.format("frame%d_role%d_width%d", f.getId(), roleIdx, argumentSpan.width());
		fv.add(index(role_width), 1d);
		
		String rel = "wut";
		if(argumentSpan.after(targetSpan)) rel = "right";
		if(argumentSpan.before(targetSpan)) rel = "left";
		if(argumentSpan.overlaps(targetSpan)) rel = "cover";
		String role_head = String.format("frame%d_role%d_", f.getId(), roleIdx, rel);
		fv.add(index(role_head), 1d);
		
		return fv;
	}
	
	private int index(String featureName) {
		int s = featIdx.size();
		int i =  featIdx.lookupIndex(featureName, true);
		if(verbose && s == i)
			System.out.println("[BasicFrameElemFeatures] new max = " + s);
		return i;
	}

	@Override
	public int cardinality() {
		// TODO do some real counting
		int s = 5000;
		assert featIdx.size() < s;
		return s;
	}

}
