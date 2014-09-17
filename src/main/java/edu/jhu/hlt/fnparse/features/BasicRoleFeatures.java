package edu.jhu.hlt.fnparse.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Path.EdgeType;
import edu.jhu.hlt.fnparse.features.Path.NodeType;
import edu.jhu.hlt.fnparse.inference.HasParserParams;

public final class BasicRoleFeatures
		extends AbstractFeatures<BasicRoleFeatures>
		implements Features.R {

	private static final long serialVersionUID = 1L;

	// If true, will implement a larger feature set that will add products of
	// words with their POS as pieces of features.
	private static boolean useFullString = false;

	// If true, use the lemma of a target/arg headword instead of the word
	// itself in the feature templates.
	private static boolean useLemmasInsteadOfWords = false;

	public BasicRoleFeatures(HasParserParams globalParams) {
		super(globalParams);

		// I think I got a bit carried away with the weights on this one
		weightingPower = 0.25d;
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

		String fs = "f" + (useFastFeaturenames ? f.getId() : f.getName());
		// Use this instead of int because same role (string) may not have the
		// same index across frames
		String rs = "r" + f.getRoleSafe(roleIdx);
		String fsrs = fs + "-" + rs;
		LexicalUnit tHead = useLemmasInsteadOfWords ? sent.getLemmaLU(targetHead) : sent.getLU(targetHead);
		boolean argRealized = argHead < sent.size();
		LexicalUnit aHead = !argRealized
				? AbstractFeatures.luEnd
				: (useLemmasInsteadOfWords ? sent.getLemmaLU(argHead) : sent.getLU(argHead));

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
		b(fv, r, 1d, fsrs, "roleHead", aHead.word);
		b(fv, r, 2d, fsrs, "roleHead", aHead.pos);
		if(useFullString) {
			b(fv, r, 1d, rs, "roleHead", aHead.getFullString());
			b(fv, r, 1d, fsrs, "roleHead", aHead.getFullString());
		}

		b(fv, r, 1d, rs, "targetHead", tHead.word);
		b(fv, r, 2d, rs, "targetHead", tHead.pos);
		b(fv, r, 1d, fsrs, "targetHead", tHead.word);
		b(fv, r, 2d, fsrs, "targetHead", tHead.pos);
		if(useFullString) {
			b(fv, r, 1d, rs, "targetHead", tHead.getFullString());
			b(fv, r, 1d, fsrs, "targetHead", tHead.getFullString());
		}

		if(useFullString) {
			b(fv, r, 0.25d, rs, "argHead", aHead.getFullString(), "targetHead", tHead.getFullString());
			b(fv, r, 0.5d, rs, "argHead", aHead.getFullString(), "targetHead", tHead.word);
			b(fv, r, 1d, rs, "argHead", aHead.getFullString(), "targetHead", tHead.pos);
			b(fv, r, 0.75d, rs, "argHead", aHead.word, "targetHead", tHead.getFullString());
		}
		b(fv, r, 1d, rs, "argHead", aHead.word, "targetHead", tHead.word);
		b(fv, r, 2d, rs, "argHead", aHead.word, "targetHead", tHead.pos);
		if(useFullString)
			b(fv, r, 1d, rs, "argHead", aHead.pos, "targetHead", tHead.getFullString());
		b(fv, r, 2d, rs, "argHead", aHead.pos, "targetHead", tHead.word);
		b(fv, r, 4d, rs, "argHead", aHead.pos, "targetHead", tHead.pos);
		if(useFullString) {
			b(fv, r, 0.25d, fsrs, "argHead", aHead.getFullString(), "targetHead", tHead.getFullString());
			b(fv, r, 0.5d, fsrs, "argHead", aHead.getFullString(), "targetHead", tHead.word);
			b(fv, r, 1d, fsrs, "argHead", aHead.getFullString(), "targetHead", tHead.pos);
			b(fv, r, 0.75d, fsrs, "argHead", aHead.word, "targetHead", tHead.getFullString());
			b(fv, r, 1d, fsrs, "argHead", aHead.pos, "targetHead", tHead.getFullString());
		}
		b(fv, r, 1d, fsrs, "argHead", aHead.word, "targetHead", tHead.word);
		b(fv, r, 2d, fsrs, "argHead", aHead.word, "targetHead", tHead.pos);
		b(fv, r, 2d, fsrs, "argHead", aHead.pos, "targetHead", tHead.word);
		b(fv, r, 4d, fsrs, "argHead", aHead.pos, "targetHead", tHead.pos);

		// Words around target and arg heads
		if (globalParams.getParserParams().useOverfittingFeatures) {
			for(int targetOffset : Arrays.asList(-2, -1, 1, 2)) {
				for(int argOffset : Arrays.asList(-2, -1, 1, 2)) {
					if(Math.abs(targetOffset) + Math.abs(argOffset) > 3)
						continue;	// don't allow (+/-2, +/-2) for fewer features

					String t = "targetOffset=" + targetOffset;
					String a = "argOffset=" + argOffset;
					LexicalUnit tLU = AbstractFeatures.getLUSafe(
							targetHead + targetOffset, sent);
					LexicalUnit aLU = AbstractFeatures.getLUSafe(
							argHead + argOffset, sent);
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
		}

		// Dependency tree features
		if(globalParams.getParserParams().useSyntaxFeatures) {
			if(argRealized && sent.governor(argHead) == targetHead) {
				b(fv, r, 3d, "trigger-arg-dep");
				b(fv, r, 2d, rs, "trigger-arg-dep");
				b(fv, r, 2d, fsrs, "trigger-arg-dep");
			}

			//timer.get(pathFeatKey, true).printIterval = 1;
			//timer.start(pathFeatKey);
			List<String> addTo = new ArrayList<String>();
			for(Path p : Arrays.asList(
					new Path(sent, targetHead, argHead, NodeType.POS, EdgeType.DEP)
					, new Path(sent, targetHead, argHead, NodeType.LEMMA, EdgeType.DIRECTION)
					//new Path(sent, targetHead, argHead, NodeType.NONE, EdgeType.DEP)
					//new Path(sent, targetHead, argHead, NodeType.NONE, EdgeType.DIRECTION)
					)) {

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





