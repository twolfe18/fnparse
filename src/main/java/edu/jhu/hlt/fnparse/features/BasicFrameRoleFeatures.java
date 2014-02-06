package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.indexing.Bob;
import edu.jhu.hlt.fnparse.indexing.Joe;
import edu.jhu.hlt.fnparse.indexing.JoeInfo;
import edu.jhu.hlt.fnparse.indexing.SuperBob;
import edu.jhu.hlt.fnparse.inference.factors.FrameRoleFactor;
import edu.jhu.util.Alphabet;

public class BasicFrameRoleFeatures implements FrameRoleFactor.Features,
		edu.jhu.hlt.fnparse.features.Features.FR, Joe<JoeInfo> {

	public static final FeatureVector emptyFeatures = new FeatureVector();
	
	private Bob<JoeInfo> bob;
//	private HeadFinder hf = new BraindeadHeadFinder();
	private Alphabet<String> featIdx = new Alphabet<String>();
	public boolean verbose = false;
	
	@SuppressWarnings("unchecked")
	public BasicFrameRoleFeatures() {
		bob = (Bob<JoeInfo>) SuperBob.getBob(this);
	}
	
	@Override
	public String getDescription() { return "BasicTargetRoleFeatures"; }

	@Override
	public String getFeatureName(int featIdx) {
		return this.featIdx.lookupObject(featIdx);
	}

	// promote
	public FeatureVector getFeaturesSimple(Frame f, int argumentHead, int targetHead, int roleIdx, Sentence sent) {
		return getFeatures(f, Span.widthOne(argumentHead), Span.widthOne(targetHead), roleIdx, sent);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, boolean argIsRealized, int targetHeadIdx, int roleIdx, int argHeadIdx, Sentence s) {

		// we'll just say that if the arg is not realized, then we return an empty feature vec
		FeatureVector fv = argIsRealized
				? getFeatures(f, Span.widthOne(argHeadIdx), Span.widthOne(targetHeadIdx), roleIdx, s)
				: emptyFeatures;
		return bob.doYourThing(fv, this);
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

	private JoeInfo joeInfo;

	@Override
	public String getJoeName() {
		return this.getClass().getName();
	}

	@Override
	public void storeJoeInfo(JoeInfo info) { joeInfo = info; }

	@Override
	public JoeInfo getJoeInfo() { return joeInfo; }
}
