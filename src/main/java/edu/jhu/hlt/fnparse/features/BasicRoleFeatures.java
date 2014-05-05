package edu.jhu.hlt.fnparse.features;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.features.Path.EdgeType;
import edu.jhu.hlt.fnparse.features.Path.NodeType;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.util.MultiTimer;

public final class BasicRoleFeatures extends AbstractFeatures<BasicRoleFeatures> implements Features.R {

	private static final long serialVersionUID = 1L;
	
	private static final String pathFeatKey = "BasicRoleFeatures:path-features";
	private static final MultiTimer timer = new MultiTimer();
	
	public BasicRoleFeatures(ParserParams params) {
		super(params);
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
		String rs = "r" + f.getRoleSafe(roleIdx);	// use this instead of int because same role (string) may not have the same index across frames
		String fsrs = fs + "-" + rs;
		LexicalUnit tHead = sent.getLU(targetHead);
		boolean argRealized = argHead < sent.size();
		LexicalUnit aHead = !argRealized ? AbstractFeatures.luEnd : sent.getLU(argHead);
		
		b(fv, r, 5d, rs, "intercept");
		b(fv, r, 5d, fsrs, "intercept");

		final double dirWeight = 3d;
		if(argHead > targetHead) {
			b(fv, r, dirWeight, rs, "arg_after_target");
			b(fv, r, dirWeight, fsrs, "arg_after_target");
		}
		else if(argHead < targetHead) {
			b(fv, r, dirWeight, rs, "arg_before_target");
			b(fv, r, dirWeight, fsrs, "arg_before_target");
		}
		else {
			b(fv, r, dirWeight, rs, "arg==target");
			b(fv, r, dirWeight, fsrs, "arg==target");
		}
		
		b(fv, r, 1d, rs, "roleHead", aHead.word);
		b(fv, r, 2d, rs, "roleHead", aHead.pos);
		b(fv, r, 1d, rs, "roleHead", aHead.getFullString());
		b(fv, r, 1d, fsrs, "roleHead", aHead.word);
		b(fv, r, 2d, fsrs, "roleHead", aHead.pos);
		b(fv, r, 1d, fsrs, "roleHead", aHead.getFullString());
		
		b(fv, r, 1d, rs, "targetHead", tHead.word);
		b(fv, r, 2d, rs, "targetHead", tHead.pos);
		b(fv, r, 1d, rs, "targetHead", tHead.getFullString());
		b(fv, r, 1d, fsrs, "targetHead", tHead.word);
		b(fv, r, 2d, fsrs, "targetHead", tHead.pos);
		b(fv, r, 1d, fsrs, "targetHead", tHead.getFullString());
		
		b(fv, r, 0.25d, rs, "argHead", aHead.getFullString(), "targetHead", tHead.getFullString());
		b(fv, r, 0.5d, rs, "argHead", aHead.getFullString(), "targetHead", tHead.word);
		b(fv, r, 1d, rs, "argHead", aHead.getFullString(), "targetHead", tHead.pos);
		b(fv, r, 0.75d, rs, "argHead", aHead.word, "targetHead", tHead.getFullString());
		b(fv, r, 1d, rs, "argHead", aHead.word, "targetHead", tHead.word);
		b(fv, r, 2d, rs, "argHead", aHead.word, "targetHead", tHead.pos);
		b(fv, r, 1d, rs, "argHead", aHead.pos, "targetHead", tHead.getFullString());
		b(fv, r, 2d, rs, "argHead", aHead.pos, "targetHead", tHead.word);
		b(fv, r, 4d, rs, "argHead", aHead.pos, "targetHead", tHead.pos);
		b(fv, r, 0.25d, fsrs, "argHead", aHead.getFullString(), "targetHead", tHead.getFullString());
		b(fv, r, 0.5d, fsrs, "argHead", aHead.getFullString(), "targetHead", tHead.word);
		b(fv, r, 1d, fsrs, "argHead", aHead.getFullString(), "targetHead", tHead.pos);
		b(fv, r, 0.75d, fsrs, "argHead", aHead.word, "targetHead", tHead.getFullString());
		b(fv, r, 1d, fsrs, "argHead", aHead.word, "targetHead", tHead.word);
		b(fv, r, 2d, fsrs, "argHead", aHead.word, "targetHead", tHead.pos);
		b(fv, r, 1d, fsrs, "argHead", aHead.pos, "targetHead", tHead.getFullString());
		b(fv, r, 2d, fsrs, "argHead", aHead.pos, "targetHead", tHead.word);
		b(fv, r, 4d, fsrs, "argHead", aHead.pos, "targetHead", tHead.pos);
		
		// words around target and arg heads
		for(int targetOffset : Arrays.asList(-2, -1, 1, 2)) {
			for(int argOffset : Arrays.asList(-2, -1, 1, 2)) {
				if(Math.abs(targetOffset) + Math.abs(argOffset) > 3)
					continue;	// don't allow (+/-2, +/-2) for fewer features
				
				String t = "targetOffset=" + targetOffset;
				String a = "argOffset=" + argOffset;
				LexicalUnit tLU = AbstractFeatures.getLUSafe(targetHead + targetOffset, sent);
				LexicalUnit aLU = AbstractFeatures.getLUSafe(argHead + argOffset, sent);
				b(fv, r, rs, t, tLU.word, a, aLU.word);
				b(fv, r, rs, t, tLU.pos,  a, aLU.word);
				b(fv, r, rs, t, tLU.word, a, aLU.pos);
				b(fv, r, rs, t, tLU.pos,  a, aLU.pos);
				b(fv, r, fsrs, t, tLU.word, a, aLU.word);
				b(fv, r, fsrs, t, tLU.pos,  a, aLU.word);
				b(fv, r, fsrs, t, tLU.word, a, aLU.pos);
				b(fv, r, fsrs, t, tLU.pos,  a, aLU.pos);
			}
		}
		
		// dependency tree features
		if(params.useSyntaxFeatures) {

			if(argRealized && sent.governor(argHead) == targetHead) {
				b(fv, r, 3d, "trigger-arg-dep");
				b(fv, r, 2d, rs, "trigger-arg-dep");
				b(fv, r, 2d, fsrs, "trigger-arg-dep");
			}
			
			//timer.get(pathFeatKey, true).printIterval = 1;
			//timer.start(pathFeatKey);
			List<String> addTo = new ArrayList<String>();
			for(Path p : Arrays.asList(
					new Path(sent, targetHead, argHead, NodeType.POS, EdgeType.DEP),
					new Path(sent, targetHead, argHead, NodeType.LEMMA, EdgeType.DIRECTION),
					new Path(sent, targetHead, argHead, NodeType.NONE, EdgeType.DEP),
					new Path(sent, targetHead, argHead, NodeType.NONE, EdgeType.DIRECTION))) {

				String ps = p.getPath();
				b(fv, r, rs, "path", ps);
				b(fv, r, fsrs, "path", ps);

				addTo.clear();
				p.pathNGrams(4, addTo, "5-paths");
				for(String pathPiece : addTo)
					b(fv, r, rs, "p5", pathPiece);

				addTo.clear();
				p.pathNGrams(4, addTo, "4-paths");
				for(String pathPiece : addTo)
					b(fv, r, rs, "p4", pathPiece);

				addTo.clear();
				p.pathNGrams(3, addTo, "3-paths");
				for(String pathPiece : addTo)
					b(fv, r, 2d, rs, "p3", pathPiece);

				addTo.clear();
				p.pathNGrams(3, addTo, "2-paths");
				for(String pathPiece : addTo)
					b(fv, r, 2d, rs, "p2", pathPiece);
			}
			//timer.stop(pathFeatKey);
		}
	}

}





