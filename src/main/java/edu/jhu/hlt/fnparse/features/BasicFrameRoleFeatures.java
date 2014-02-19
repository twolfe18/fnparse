package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.heads.BraindeadHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.util.Alphabet;

public class BasicFrameRoleFeatures extends AbstractFeatures implements edu.jhu.hlt.fnparse.features.Features.FRE {

	private HeadFinder hf = new BraindeadHeadFinder();
	public boolean verbose = false;
	
	public BasicFrameRoleFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, boolean argIsRealized, int targetHeadIdx, int roleIdx, Span argumentSpan, Sentence sent) {
		
		if(argIsRealized && roleIdx >= f.numRoles())
			throw new IllegalArgumentException();
		
		// NOTE: don't write any back-off features that only look at roleIdx because it is
		// meaningless outside without considering the frame.
		
		// TODO rewrite this so that it doesn't think its dealing with spans
		Span targetSpan = Span.widthOne(targetHeadIdx);
		
		LexicalUnit x;
		FeatureVector fv = new FeatureVector();
		
		String fs = "f" + f.getId();
		String rs = "r" + roleIdx;
		String fsrs = fs + "-" + rs;
		LexicalUnit tHead = sent.getLU(targetSpan.end-1);
		LexicalUnit rHead = sent.getLU(hf.head(argumentSpan, sent));
		assert targetSpan.width() == 1 : "update this code";
		
		b(fv, "intercept-" + fsrs);
		
		b(fv, fsrs + "-width=" + argumentSpan.width());

		if(argumentSpan.after(targetSpan))
			b(fv, fsrs + "-arg_after_target");
		if(argumentSpan.before(targetSpan))
			b(fv, fsrs + "-arg_before_target");
		if(argumentSpan.overlaps(targetSpan))
			b(fv, fsrs + "-arg_overlaps_target");
		if(argumentSpan.equals(targetSpan))
			b(fv, fsrs + "-arg_equals_target");
		
		
		b(fv, fsrs + "-roleHead=" + rHead.word);
		b(fv, fsrs + "-roleHead=" + rHead.pos);
		b(fv, fsrs + "-roleHead=" + rHead.getFullString());
		b(fv, fsrs + "-targetHead=" + tHead.word);
		b(fv, fsrs + "-targetHead=" + tHead.pos);
		b(fv, fsrs + "-targetHead=" + tHead.getFullString());
		b(fv, fsrs + "-roleHead=" + rHead.word + "-targetHead=" + tHead.word);
		b(fv, fsrs + "-roleHead=" + rHead.word + "-targetHead=" + tHead.pos);
		b(fv, fsrs + "-roleHead=" + rHead.pos + "-targetHead=" + tHead.word);
		b(fv, fsrs + "-roleHead=" + rHead.pos + "-targetHead=" + tHead.pos);
		b(fv, fsrs + "-roleHead=" + rHead + "-targetHead=" + tHead.getFullString());
		b(fv, fsrs + "-roleHead=" + rHead + "-targetHead=" + tHead.word);
		b(fv, fsrs + "-roleHead=" + rHead + "-targetHead=" + tHead.pos);
		b(fv, fsrs + "-roleHead=" + rHead.word + "-targetHead=" + tHead.getFullString());
		b(fv, fsrs + "-roleHead=" + rHead.pos + "-targetHead=" + tHead.getFullString());
		
		b(fv, fsrs + "-targetHead=" + tHead.word + "-argTok1=" + sent.getLU(argumentSpan.start).word);
		b(fv, fsrs + "-targetHead=" + tHead.word + "-argTokN=" + sent.getLU(argumentSpan.end-1).word);
		if(argumentSpan.width() > 1) {
			b(fv, fsrs + "-targetHead=" + tHead.word + "-argTok2=" + sent.getLU(argumentSpan.start+1).word);
			b(fv, fsrs + "-targetHead=" + tHead.word + "-argTokN-1=" + sent.getLU(argumentSpan.end-2).word);
			if(argumentSpan.width() > 2) {
				b(fv, fsrs + "-targetHead=" + tHead.word + "-argTok3=" + sent.getLU(argumentSpan.start+2).word);
				b(fv, fsrs + "-targetHead=" + tHead.word + "-argTokN-2=" + sent.getLU(argumentSpan.end-3).word);
				if(argumentSpan.width() > 3) {
					b(fv, fsrs + "-targetHead=" + tHead.word + "-argTok4=" + sent.getLU(argumentSpan.start+3).word);
					b(fv, fsrs + "-targetHead=" + tHead.word + "-argTokN-3=" + sent.getLU(argumentSpan.end-4).word);
				}
			}
		}
		
		for(int i=argumentSpan.start; i<argumentSpan.end; i++) {
			b(fv, fsrs + "-targetHead=" + tHead.getFullString() + "-argContains=" + sent.getLU(i).getFullString());
			b(fv, fsrs + "-targetHead=" + tHead.getFullString() + "-argContains=" + sent.getLU(i).word);
			b(fv, fsrs + "-targetHead=" + tHead.getFullString() + "-argContains=" + sent.getLU(i).pos);
			b(fv, fsrs + "-targetHead=" + tHead.word + "-argContains=" + sent.getLU(i).getFullString());
			b(fv, fsrs + "-targetHead=" + tHead.word + "-argContains=" + sent.getLU(i).word);
			b(fv, fsrs + "-targetHead=" + tHead.word + "-argContains=" + sent.getLU(i).pos);
		}
		
		x = argumentSpan.start == 0 ? luStart : sent.getLU(argumentSpan.start-1);
		b(fv, fsrs + "-targetHead=" + tHead.word + "-leftOfArg=" + x.getFullString());
		b(fv, fsrs + "-targetHead=" + tHead.word + "-leftOfArg=" + x.word);
		b(fv, fsrs + "-targetHead=" + tHead.word + "-leftOfArg=" + x.pos);

		x = argumentSpan.end == sent.size() ? luEnd : sent.getLU(argumentSpan.end);
		b(fv, fsrs + "-targetHead=" + tHead.word + "-rightOfArg=" + x.getFullString());
		b(fv, fsrs + "-targetHead=" + tHead.word + "-rightOfArg=" + x.word);
		b(fv, fsrs + "-targetHead=" + tHead.word + "-rightOfArg=" + x.pos);

		return fv;
	}
}
