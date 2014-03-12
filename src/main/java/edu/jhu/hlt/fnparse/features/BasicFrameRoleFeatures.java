package edu.jhu.hlt.fnparse.features;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;

public final class BasicFrameRoleFeatures extends AbstractFeatures<BasicFrameRoleFeatures> implements Features.FR {

	private static final long serialVersionUID = 1L;
	
	private ParserParams params;
	
	public BasicFrameRoleFeatures(ParserParams params) {
		super(params.featIdx);
		this.params = params;
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, boolean argIsRealized, int targetHead, int roleIdx, int argHead, Sentence sent) {
		
		if(argIsRealized && f == Frame.nullFrame)
			throw new IllegalArgumentException();
		
		if(argIsRealized && roleIdx >= f.numRoles())
			throw new IllegalArgumentException();
		
		// NOTE: don't write any back-off features that only look at just roleIdx
		// because it is meaningless outside without considering the frame.
		
		FeatureVector fv = new FeatureVector();
		
		String fs = "f" + (params.fastFeatNames ? f.getId() : f.getName());
		String rs = "r" + (params.fastFeatNames ? roleIdx : f.getRoleSafe(roleIdx));
		String as = argIsRealized ? "-isRealize" : "-notRealized";
		String fsrs = fs + "-" + rs + as;
		LexicalUnit tHead = sent.getLU(targetHead);
		LexicalUnit aHead = sent.getLU(argHead);
		
		b(fv, fsrs, "intercept");

		if(argHead > targetHead)
			b(fv, fsrs, "arg_after_target");
		else if(argHead < targetHead)
			b(fv, fsrs, "arg_before_target");
		else
			b(fv, fsrs, "arg=target");
		
		b(fv, fsrs, "roleHead=", aHead.word);
		b(fv, fsrs, "roleHead=", aHead.pos);
		b(fv, fsrs, "roleHead=", aHead.getFullString());
		
		b(fv, fsrs, "targetHead=", tHead.word);
		b(fv, fsrs, "targetHead=", tHead.pos);
		b(fv, fsrs, "targetHead=", tHead.getFullString());
		
		b(fv, fsrs, "argHead=", aHead.getFullString(), "targetHead=", tHead.getFullString());
		b(fv, fsrs, "argHead=", aHead.getFullString(), "targetHead=", tHead.word);
		b(fv, fsrs, "argHead=", aHead.getFullString(), "targetHead=", tHead.pos);
		b(fv, fsrs, "argHead=", aHead.word, "targetHead=", tHead.getFullString());
		b(fv, fsrs, "argHead=", aHead.word, "targetHead=", tHead.word);
		b(fv, fsrs, "argHead=", aHead.word, "targetHead=", tHead.pos);
		b(fv, fsrs, "argHead=", aHead.pos, "targetHead=", tHead.getFullString());
		b(fv, fsrs, "argHead=", aHead.pos, "targetHead=", tHead.word);
		b(fv, fsrs, "argHead=", aHead.pos, "targetHead=", tHead.pos);
		
		// words around target and arg heads
		for(int targetOffset : Arrays.asList(-2, -1, 1, 2)) {
			for(int argOffset : Arrays.asList(-2, -1, 1, 2)) {
				if(Math.abs(targetOffset) + Math.abs(argOffset) > 3)
					continue;	// don't allow (+/-2, +/-2) for fewer features
				
				String t = "targetOffset=" + targetOffset;
				String a = "argOffset=" + argOffset;
				LexicalUnit tLU = AbstractFeatures.getLUSafe(targetHead + targetOffset, sent);
				LexicalUnit aLU = AbstractFeatures.getLUSafe(argHead + argOffset, sent);
				b(fv, fsrs, t, tLU.word, a, aLU.word);
				b(fv, fsrs, t, tLU.pos,  a, aLU.word);
				b(fv, fsrs, t, tLU.word, a, aLU.pos);
				b(fv, fsrs, t, tLU.pos,  a, aLU.pos);
			}
		}
		
		// dependency tree features
		if(params.useSyntaxFeatures) {
			if(sent.governor(argHead) == targetHead)
				b(fv, "trigger-arg-dep", as);
			// TODO more?
		}

		return fv;
	}
}
