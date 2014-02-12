package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.indexing.BasicBob;
import edu.jhu.hlt.fnparse.features.indexing.Joe;
import edu.jhu.hlt.fnparse.features.indexing.JoeInfo;
import edu.jhu.hlt.fnparse.features.indexing.SuperBob;
import edu.jhu.hlt.fnparse.inference.heads.BraindeadHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.util.Alphabet;

public class BasicFrameRoleFeatures implements edu.jhu.hlt.fnparse.features.Features.FRE, Joe<JoeInfo> {

	public static final FeatureVector emptyFeatures = new FeatureVector();
	
	private BasicBob bob;
	private Alphabet<String> featIdx;
	private HeadFinder hf = new BraindeadHeadFinder();
	public boolean verbose = false;
	
	private LexicalUnit luStart = new LexicalUnit("<S>", "<S>");
	private LexicalUnit luEnd = new LexicalUnit("</S>", "</S>");
	
	public BasicFrameRoleFeatures() {
		bob = (BasicBob) SuperBob.getBob(this);
		featIdx = bob.trackMyAlphabet(this);
	}

	// promote
	public FeatureVector getFeaturesSimple(Frame f, int argumentHead, int targetHead, int roleIdx, Sentence sent) {
		return getFeatures(f, Span.widthOne(argumentHead), Span.widthOne(targetHead), roleIdx, sent);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, boolean argIsRealized, int targetHeadIdx, int roleIdx, Span argument, Sentence s) {

		// we'll just say that if the arg is not realized, then we return an empty feature vec
		FeatureVector fv = argIsRealized
				? getFeatures(f, argument, Span.widthOne(targetHeadIdx), roleIdx, s)
				: emptyFeatures;
		return bob.doYourThing(fv, this);
	}
	
	public FeatureVector getFeatures(Frame f, Span argumentSpan, Span targetSpan, int roleIdx, Sentence sent) {
		
		if(roleIdx >= f.numRoles())
			throw new IllegalArgumentException();
		
		// NOTE: don't write any back-off features that only look at roleIdx because it is
		// meaningless outside without considering the frame.
		
		LexicalUnit x;
		FeatureVector fv = new FeatureVector();
		
		String fs = "f" + f.getId();
		String rs = "r" + roleIdx;
		LexicalUnit tHead = sent.getLU(targetSpan.end-1);
		LexicalUnit rHead = sent.getLU(hf.head(argumentSpan, sent));
		assert targetSpan.width() == 1 : "update this code";
		
		fv.add(index("intercept-" + fs + "-" + rs), 1d);
		
		fv.add(index(fs + "-" + rs + "-width=" + argumentSpan.width()), 1d);

		if(argumentSpan.after(targetSpan))
			fv.add(index(fs + "-" + rs + "-arg_after_target"), 1d);
		if(argumentSpan.before(targetSpan))
			fv.add(index(fs + "-" + rs + "-arg_before_target"), 1d);
		if(argumentSpan.overlaps(targetSpan))
			fv.add(index(fs + "-" + rs + "-arg_overlaps_target"), 1d);
		if(argumentSpan.equals(targetSpan))
			fv.add(index(fs + "-" + rs + "-arg_equals_target"), 1d);
		
		
		fv.add(index(fs + "-" + rs + "-roleHead=" + rHead.word), 1d);
		fv.add(index(fs + "-" + rs + "-roleHead=" + rHead.pos), 1d);
		fv.add(index(fs + "-" + rs + "-roleHead=" + rHead.getFullString()), 1d);
		fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word), 1d);
		fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.pos), 1d);
		fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.getFullString()), 1d);
		fv.add(index(fs + "-" + rs + "-roleHead=" + rHead.word + "-targetHead=" + tHead.word), 1d);
		fv.add(index(fs + "-" + rs + "-roleHead=" + rHead.word + "-targetHead=" + tHead.pos), 1d);
		fv.add(index(fs + "-" + rs + "-roleHead=" + rHead.pos + "-targetHead=" + tHead.word), 1d);
		fv.add(index(fs + "-" + rs + "-roleHead=" + rHead.pos + "-targetHead=" + tHead.pos), 1d);
		fv.add(index(fs + "-" + rs + "-roleHead=" + rHead + "-targetHead=" + tHead.getFullString()), 1d);
		fv.add(index(fs + "-" + rs + "-roleHead=" + rHead + "-targetHead=" + tHead.word), 1d);
		fv.add(index(fs + "-" + rs + "-roleHead=" + rHead + "-targetHead=" + tHead.pos), 1d);
		fv.add(index(fs + "-" + rs + "-roleHead=" + rHead.word + "-targetHead=" + tHead.getFullString()), 1d);
		fv.add(index(fs + "-" + rs + "-roleHead=" + rHead.pos + "-targetHead=" + tHead.getFullString()), 1d);
		
		fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-argTok1=" + sent.getLU(argumentSpan.start).word), 1d);
		fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-argTokN=" + sent.getLU(argumentSpan.end-1).word), 1d);
		if(argumentSpan.width() > 1) {
			fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-argTok2=" + sent.getLU(argumentSpan.start+1).word), 1d);
			fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-argTokN-1=" + sent.getLU(argumentSpan.end-2).word), 1d);
			if(argumentSpan.width() > 2) {
				fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-argTok3=" + sent.getLU(argumentSpan.start+2).word), 1d);
				fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-argTokN-2=" + sent.getLU(argumentSpan.end-3).word), 1d);
				if(argumentSpan.width() > 3) {
					fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-argTok4=" + sent.getLU(argumentSpan.start+3).word), 1d);
					fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-argTokN-3=" + sent.getLU(argumentSpan.end-4).word), 1d);
				}
			}
		}
		
		for(int i=argumentSpan.start; i<argumentSpan.end; i++) {
			fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.getFullString() + "-argContains=" + sent.getLU(i).getFullString()), 1d);
			fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.getFullString() + "-argContains=" + sent.getLU(i).word), 1d);
			fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.getFullString() + "-argContains=" + sent.getLU(i).pos), 1d);
			fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-argContains=" + sent.getLU(i).getFullString()), 1d);
			fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-argContains=" + sent.getLU(i).word), 1d);
			fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-argContains=" + sent.getLU(i).pos), 1d);
		}
		
		x = argumentSpan.start == 0 ? luStart : sent.getLU(argumentSpan.start-1);
		fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-leftOfArg=" + x.getFullString()), 1d);
		fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-leftOfArg=" + x.word), 1d);
		fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-leftOfArg=" + x.pos), 1d);

		x = argumentSpan.end == sent.size() ? luEnd : sent.getLU(argumentSpan.end);
		fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-rightOfArg=" + x.getFullString()), 1d);
		fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-rightOfArg=" + x.word), 1d);
		fv.add(index(fs + "-" + rs + "-targetHead=" + tHead.word + "-rightOfArg=" + x.pos), 1d);

		
		// FOR DEBUGGING (REMOVE LATER)
		// this should be a feature that makes for easy over-fitting
		fv.add(index("DEBUG-" + fs + "-" + rs + "-argHeadIdx=" + argumentSpan + "-targetHeadIdx=" + targetSpan), 50d);
		
		return fv;
	}
	
	private int index(String featureName) {
		int s = featIdx.size();
		int i =  featIdx.lookupIndex(featureName, true);
		if(verbose && s == i)
			System.out.println("[BasicFrameElemFeatures] new max = " + s);
		return i;
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
