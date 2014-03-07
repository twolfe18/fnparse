package edu.jhu.hlt.fnparse.features;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.util.Alphabet;

public final class BasicFrameRoleFeatures extends AbstractFeatures<BasicFrameRoleFeatures> implements Features.FR {

	/**
	 * if false, use frame and role names instead of their indices
	 */
	public final boolean fastFeatNames = true;
	
	public boolean verbose = false;
	
	public BasicFrameRoleFeatures(Alphabet<String> featIdx) {
		super(featIdx);
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
		
		String fs = "f" + (fastFeatNames ? f.getId() : f.getName());
		String rs = "r" + (fastFeatNames ? roleIdx : f.getRoleSafe(roleIdx));
		String fsrs = fs + "-" + rs + (argIsRealized ? "-isRealize" : "-isntRealized");
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
		
		// TODO dependency tree features

		return fv;
	}
}
