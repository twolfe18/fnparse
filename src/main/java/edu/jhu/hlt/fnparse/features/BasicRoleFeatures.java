package edu.jhu.hlt.fnparse.features;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;

public final class BasicRoleFeatures extends AbstractFeatures<BasicRoleFeatures> implements Features.R {

	private static final long serialVersionUID = 1L;
	
	private ParserParams params;
	
	public BasicRoleFeatures(ParserParams params) {
		super(params.featIdx);
		this.params = params;
	}
	
	@Override
	public void featurize(FeatureVector fv, Refinements r, int targetHead, Frame f, int argHead, int roleIdx, Sentence sent) {
		
		if(roleIdx >= f.numRoles())
			throw new IllegalArgumentException();
		
		// NOTE: don't write any back-off features that only look at just roleIdx
		// because it is meaningless outside without considering the frame.
		// CORRECTION: the way they named the roles in framenet, there is frame-to-frame overlap of
		// roles, so it may be a good idea to allow backing off from the frame,
		// ***but only if you're not using params.fastFeatNames!!!***
		// (if you are, you will get an int, rather than a role name, which will not generalize across frames)
		
		String fs = "f" + (params.fastFeatNames ? f.getId() : f.getName());
		String rs = "r" + (params.fastFeatNames ? roleIdx : f.getRoleSafe(roleIdx));
		String fsrs = fs + "-" + rs;
		LexicalUnit tHead = sent.getLU(targetHead);
		LexicalUnit aHead = sent.getLU(argHead);
		
		b(fv, r, fsrs, "intercept");

		if(argHead > targetHead)
			b(fv, r, fsrs, "arg_after_target");
		else if(argHead < targetHead)
			b(fv, r, fsrs, "arg_before_target");
		else
			b(fv, r, fsrs, "arg=target");
		
		b(fv, r, fsrs, "roleHead=", aHead.word);
		b(fv, r, fsrs, "roleHead=", aHead.pos);
		b(fv, r, fsrs, "roleHead=", aHead.getFullString());
		
		b(fv, r, fsrs, "targetHead=", tHead.word);
		b(fv, r, fsrs, "targetHead=", tHead.pos);
		b(fv, r, fsrs, "targetHead=", tHead.getFullString());
		
		b(fv, r, fsrs, "argHead=", aHead.getFullString(), "targetHead=", tHead.getFullString());
		b(fv, r, fsrs, "argHead=", aHead.getFullString(), "targetHead=", tHead.word);
		b(fv, r, fsrs, "argHead=", aHead.getFullString(), "targetHead=", tHead.pos);
		b(fv, r, fsrs, "argHead=", aHead.word, "targetHead=", tHead.getFullString());
		b(fv, r, fsrs, "argHead=", aHead.word, "targetHead=", tHead.word);
		b(fv, r, fsrs, "argHead=", aHead.word, "targetHead=", tHead.pos);
		b(fv, r, fsrs, "argHead=", aHead.pos, "targetHead=", tHead.getFullString());
		b(fv, r, fsrs, "argHead=", aHead.pos, "targetHead=", tHead.word);
		b(fv, r, fsrs, "argHead=", aHead.pos, "targetHead=", tHead.pos);
		
		// words around target and arg heads
		for(int targetOffset : Arrays.asList(-2, -1, 1, 2)) {
			for(int argOffset : Arrays.asList(-2, -1, 1, 2)) {
				if(Math.abs(targetOffset) + Math.abs(argOffset) > 3)
					continue;	// don't allow (+/-2, +/-2) for fewer features
				
				String t = "targetOffset=" + targetOffset;
				String a = "argOffset=" + argOffset;
				LexicalUnit tLU = AbstractFeatures.getLUSafe(targetHead + targetOffset, sent);
				LexicalUnit aLU = AbstractFeatures.getLUSafe(argHead + argOffset, sent);
				b(fv, r, fsrs, t, tLU.word, a, aLU.word);
				b(fv, r, fsrs, t, tLU.pos,  a, aLU.word);
				b(fv, r, fsrs, t, tLU.word, a, aLU.pos);
				b(fv, r, fsrs, t, tLU.pos,  a, aLU.pos);
			}
		}
		
		// dependency tree features
		if(params.useSyntaxFeatures) {
			if(sent.governor(argHead) == targetHead)
				b(fv, r, "trigger-arg-dep");
			// TODO more?
		}
	}

}
