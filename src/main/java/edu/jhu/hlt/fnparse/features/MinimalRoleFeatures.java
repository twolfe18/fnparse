package edu.jhu.hlt.fnparse.features;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.Path.EdgeType;
import edu.jhu.hlt.fnparse.features.Path.NodeType;
import edu.jhu.hlt.fnparse.inference.HasParserParams;
import edu.jhu.hlt.fnparse.util.Counts;
import edu.jhu.hlt.fnparse.util.PosPatternGenerator;
import edu.jhu.hlt.fnparse.util.PosPatternGenerator.Mode;

public class MinimalRoleFeatures
		extends AbstractFeatures<BasicRoleFeatures>
		implements Features.R {
	private static final long serialVersionUID = 1L;

	// Appears to help very little if at all with 400 training examples
	public boolean useLRcontext = false;

	public MinimalRoleFeatures(HasParserParams globalParams) {
		super(globalParams);
		super.weightingPower = 1d;
	}

	@Override
	public void featurize(
			FeatureVector v,
			Refinements r,
			int i,
			Frame t,
			int j,
			int k,
			Sentence s) {
		LexicalUnit target = s.getLemmaLU(i);
		LexicalUnit arg = s.getLemmaLU(j);
		boolean argRealized = j < s.size();

		String role = t.getRole(k);
		if (!argRealized)
			role += "-notRealized";
		String frameRole = t.getName() + "." + role;
		String passive = s.passive(i) ? "passive" : "active";

		b(v, r, "intercept");
		b(v, r, role);
		b(v, r, frameRole);

		if (argRealized) {
			String roleType = "roleType=" + t.getRoleType(k);
			b(v, r, roleType);
			b(v, r, roleType, t.getName());
		}

		b(v, r, passive);
		b(v, r, role, passive);
		b(v, r, frameRole, passive);

		b(v, r, "arg=" + arg.pos);
		b(v, r, "arg=" + arg.pos, passive);
		b(v, r, "arg=" + arg.pos, "target=" + target.pos);
		b(v, r, "arg=" + arg.pos, "target=" + target.pos, passive);
		b(v, r, "target=" + target.pos);

		b(v, r, role, "target=" + target.word);			// helps
		b(v, r, role, "target=" + target.pos);
		//b(v, r, frameRole, "target=" + target.word);	// hurts
		//b(v, r, frameRole, "target=" + target.pos);
		b(v, r, role, "arg=" + arg.word);	// TODO try removing this
		b(v, r, role, "arg=" + arg.pos);
		b(v, r, frameRole, "arg=" + arg.word);
		b(v, r, frameRole, "arg=" + arg.pos);

		// word versions don't help test loss
		b(v, r, role, "arg=" + arg.pos, passive);
		b(v, r, frameRole, "arg=" + arg.pos, passive);

		String dir = (i < j) ? "rightArg" : ((i > j) ? "leftArg" : "selfArg");
		b(v, r, dir, "target=" + target.word);
		b(v, r, dir, "target=" + target.pos);
		b(v, r, dir, "arg=" + arg.word);
		b(v, r, dir, "arg=" + arg.pos);
		b(v, r, dir, "target=" + target.pos, "pos=" + arg.pos);
		b(v, r, role, dir);
		b(v, r, role, dir, "target=" + target.pos);
		b(v, r, role, dir, "arg=" + arg.pos);
		b(v, r, frameRole, dir);
		b(v, r, frameRole, dir, "target=" + target.pos);
		b(v, r, frameRole, dir, "arg=" + arg.pos);

		// Doesn't help...
		//b(v, r, dir, passive);
		//b(v, r, role, dir, passive);
		//b(v, r, frameRole, dir, passive);

		// Sentence length
		//String sentLen = sentenceLengthBuckets(s.size());
		//b(v, r, sentLen);
		//b(v, r, role, sentLen);
		//b(v, r, frameRole, sentLen);

		// Words between target and argument
		if (i != j) {
			//between(v, r, "betweenTargetAndArg",
			//		Math.min(i, j) + 1, Math.max(i, j), s);
			between(v, r, role + "-betweenTargetAndArg",
					Math.min(i, j) + 1, Math.max(i, j), s);
			between(v, r, frameRole + "-betweenTargetAndArg",
					Math.min(i, j) + 1, Math.max(i, j), s);
		}

		// Words from the start of the sentence to the arg
		// (this hurts performance)
		//between(v, r, "fromStartOfSent-" + dir, 0, Math.min(i, j), s);
		//between(v, r, role + "-fromStartOfSent-" + dir, 0, Math.min(i, j), s);
		//between(v, r, frameRole + "-fromStartOfSent-" + dir, 0, Math.min(i, j), s);

		// Words to the right of the right argument
		/*
		int nextHeadVerb = s.nextHeadVerb(i);
		if (nextHeadVerb >= 0) {
			between(v, r, "rightOfMax", j, nextHeadVerb, s);
			between(v, r, role + "-rightOfMax", j, nextHeadVerb, s);
			between(v, r, frameRole + "-rightOfMax", j, nextHeadVerb, s);
		}
		*/
		/*
		between(v, r, role + "-rightOfMax",
				Math.max(i, j), Math.min(s.size(), Math.max(i, j) + 10), s);
		between(v, r, frameRole + "-rightOfMax",
				Math.max(i, j), Math.min(s.size(), Math.max(i, j) + 10), s);
		 */

		if (useLRcontext) {
			// POS seems to help a little, lexicalized doesn't help out of sample error much
			LexicalUnit left = AbstractFeatures.getLUSafe(j - 1, s);
			b(v, r, role, "left=" + left.pos);
			b(v, r, frameRole, "left=" + left.pos);

			LexicalUnit right = AbstractFeatures.getLUSafe(j + 1, s);
			b(v, r, role, "right=" + right.pos);
			b(v, r, frameRole, "right=" + right.pos);

			String argPos = new PosPatternGenerator(2, 2, Mode.COARSE_POS)
					.extract(Span.widthOne(j), s);
			b(v, r, "aroundArg", argPos);
			b(v, r, role, "aroundArg", argPos);
			b(v, r, frameRole, "aroundArg", argPos);
		}

		if (globalParams.getParserParams().useSyntaxFeatures) {

			// Direct relation between target and argument?
			if (j < s.size() && s.governor(j) == i) {
				b(v, r, "targetToArgLink");
				b(v, r, role, "targetToArgLink");
				b(v, r, frameRole, "targetToArgLink");
			} else if (i < s.size() && s.governor(i) == j) {
				b(v, r, "argToTargetLink");
				b(v, r, role, "argToTargetLink");
				b(v, r, frameRole, "argToTargetLink");
			} else if (j < s.size() && s.governor(j) < 0) {
				b(v, r, "argIsRoot");
				b(v, r, role, "argIsRoot");
				b(v, r, frameRole, "argIsRoot");
			}

			// Parent of target
			if (i < s.size()) {
				//b(v, r, "targetParent", parentRelTo(i, i, j, s));
				//b(v, r, "targetParent", parentRelTo(i, i, j, s), s.dependencyType(i));
				//b(v, r, "targetParent", s.dependencyType(i));
				//b(v, r, role, "targetParent", parentRelTo(i, i, j, s));
				b(v, r, role, "targetParent", parentRelTo(i, i, j, s), s.dependencyType(i));
				b(v, r, role, "targetParent", s.dependencyType(i));
				//b(v, r, frameRole, "targetParent", parentRelTo(i, i, j, s));
				b(v, r, frameRole, "targetParent", parentRelTo(i, i, j, s), s.dependencyType(i));
				b(v, r, frameRole, "targetParent", s.dependencyType(i));
			}

			// Parent of argument
			if (j < s.size()) {
				//b(v, r, "argParent", parentRelTo(j, i, j, s));
				//b(v, r, "argParent", parentRelTo(j, i, j, s), s.dependencyType(j));
				//b(v, r, "argParent", s.dependencyType(j));
				//b(v, r, role, "argParent", parentRelTo(j, i, j, s));
				b(v, r, role, "argParent", parentRelTo(j, i, j, s), s.dependencyType(j));
				b(v, r, role, "argParent", s.dependencyType(j));
				//b(v, r, frameRole, "argParent", parentRelTo(j, i, j, s));
				b(v, r, frameRole, "argParent", parentRelTo(j, i, j, s), s.dependencyType(j));
				b(v, r, frameRole, "argParent", s.dependencyType(j));
			}

			// Path itself
			Path posPath = new Path(s, i, j, NodeType.POS, EdgeType.DEP);
			b(v, r, posPath.getPath());
			//b(v, r, role, posPath.getPath());
			//b(v, r, frameRole, posPath.getPath());

			// Path length
			String absPathLen = "absPathLen="
					+ semaforPathLengthBuckets(posPath.size());
			String relPathLen = "relPathLen="
					+ semaforPathLengthBuckets(posPath.deltaDepth());
			b(v, r, absPathLen);
			b(v, r, relPathLen);
			b(v, r, role, absPathLen);
			b(v, r, role, relPathLen);
			b(v, r, frameRole, absPathLen);
			b(v, r, frameRole, relPathLen);

			// Path n-grams
			int n = 2;
			Set<String> posPathNgrams = new HashSet<>();
			posPath.pathNGrams(n, posPathNgrams, "pos-dep-" + n + "gram=");
			for (String ngram : posPathNgrams) {
				b(v, r, role, ngram);
				b(v, r, frameRole, ngram);
			}

			// Depth of the argument
			String absDepth = null, relDepth = null;
			if (j < s.size() && j >= 0) {
				int jDepth = s.depth(j);
				absDepth = "argDepth=" + semaforPathLengthBuckets(jDepth);
				b(v, r, absDepth);
				b(v, r, role, absDepth);
				b(v, r, frameRole, absDepth);

				// Depth of the argument w.r.t. the target
				int iDepth = s.depth(i);
				if (iDepth > 0 && jDepth > 0) {
					relDepth = "argDepth-targetDepth="
							+ semaforPathLengthBuckets(jDepth - iDepth);
				} else if (iDepth == 0 && jDepth > 0) {
					relDepth = "argDepth=0,targetDepth="
							+ semaforPathLengthBuckets(jDepth);
				} else if (iDepth > 0 && jDepth == 0) {
					relDepth = "argDepth=" + semaforPathLengthBuckets(iDepth)
							+ ",targetDepth=0";
				} else {
					relDepth = "argDepth=0,targetDepth=0";
				}
				b(v, r, relDepth);
				b(v, r, role, relDepth);
				b(v, r, frameRole, relDepth);

				// Conjoin with dir
				b(v, r, role, absDepth, dir);
				b(v, r, role, relDepth, dir);

				// Conjoin with arg POS
				b(v, r, role, absDepth, "arg=" + arg.pos);
				b(v, r, role, relDepth, "arg=" + arg.pos);

				// Conjoin with arg POS and dir
				//b(v, r, role, absDepth, "arg=" + arg.pos, dir);	// hurts
				//b(v, r, role, relDepth, "arg=" + arg.pos, dir);

				under(v, r, role, frameRole, j, 0, j, s);

				String il = "arg-1Rel=" + parentRelTo(i-1, i, j, s);
				String ir = "arg+1Rel=" + parentRelTo(i+1, i, j, s);
				//String tl = "target-1Rel=" + parentRelTo(j-1, i, j, s);
				//String tr = "target+1Rel=" + parentRelTo(j+1, i, j, s);
				il += "_" + dir;
				ir += "_" + dir;
				//tl += "_" + dir;
				//tr += "_" + dir;
				b(v, r, il);
				b(v, r, ir);
				//b(v, r, tl);
				//b(v, r, tr);
				b(v, r, role, il);
				b(v, r, role, ir);
				//b(v, r, role, tl);
				//b(v, r, role, tr);
				b(v, r, frameRole, il);
				b(v, r, frameRole, ir);
				//b(v, r, frameRole, tl);
				//b(v, r, frameRole, tr);
			}
		}
	}

	private static String parentRelTo(int pos, int i, int j, Sentence s) {
		if (pos < 0 || pos >= s.size())
			return "none";
		int lp = s.governor(pos);
		if (lp < 0 || lp >= s.size()) {
			return "root";
		} else if (lp < Math.min(i, j)) {
			return "left";
		} else if (lp == Math.min(i, j)) {
			return "min";
		} else if (lp < Math.max(i, j)) {
			return "middle";
		} else if (lp == Math.max(i, j)) {
			return "max";
		} else {
			return "right";
		}
	}

	private void under(
			FeatureVector v,
			Refinements r,
			String role,
			String frameRole,
			int cur,
			int depth,
			int parent,
			Sentence s) {
		if (depth > 0) {
			assert cur != parent;
			String dir = (cur < parent ? "<-" : "->") + depth;
			char curC = s.getPos(cur).charAt(0);
			char parC = s.getPos(parent).charAt(0);
			if (canLexicalize(cur, s)) {
				String curS = s.getLemmaLU(cur).getFullString();
				String ds = "dominates(?," + curS + ")";
				String dds = "dominates(" + dir + "," + curS + ")";
				String ddds = "dominates(" + parC + dir + "," + curS + ")";
				b(v, r, ds);
				b(v, r, dds);
				b(v, r, ddds);
				b(v, r, role, "underArg", ds);
				b(v, r, role, "underArg", dds);
				//b(v, r, frameRole, "underArg", ds);
				//b(v, r, frameRole, "underArg", dds);
			} else {
				//String d = "dominates(?," + s.getPos(cur) + ")";
				String dd = "dominates(" + dir + "," + curC + ")";
				String ddd = "dominates(" + parC + dir + "," + curC + ")";
				//b(v, r, "underArg", d);
				b(v, r, "underArg", dd);
				b(v, r, "underArg", ddd);
				//b(v, r, role, "underArg", d);
				b(v, r, role, "underArg", dd);
				b(v, r, role, "underArg", ddd);
				//b(v, r, frameRole, "underArg", d);
				//b(v, r, frameRole, "underArg", dd);
				//b(v, r, frameRole, "underArg", ddd);
			}
		} else if (depth > 2) {
			return;
		}
		for (int child : s.childrenOf(cur)) 
			if (child != cur && child != parent)
				under(v, r, role, frameRole, child, depth + 1, parent, s);
	}

	public static String semaforPathLengthBuckets(int len) {
		if (len <= -20) return "(-inf,-20]";
		else if (len <= -10) return "(-20,-10]";
		else if (len <= -5) return "(-10,-5]";
		else if (len <= 4) return "[" + len + "]";
		else if (len < 10) return "[5,10)";
		else if (len < 20) return "[10,20)";
		else return "[20,inf)";
	}

	public static String sentenceLengthBuckets(int len) {
		if (len <= 5) return "[" + len + "]";
		else if (len <= 10) return "(5,10]";
		else if (len <= 15) return "(10,15]";
		else if (len <= 20) return "(15,20]";
		else if (len <= 25) return "(20,25]";
		else if (len <= 30) return "(25,30]";
		else if (len <= 40) return "(30,40]";
		else return "(40,inf)";
	}

	private static boolean canLexicalize(int i, Sentence s) {
		String pos = s.getPos(i);
		if (pos.startsWith("PRP")) return true;
		if (pos.equals("MD")) return true;
		if (pos.equals("CC")) return true;
		if (pos.equals("IN")) return true;
		if (pos.startsWith("W")) return true;
		if (pos.endsWith("DT")) return true;
		//if (pos.equals("SYM")) return true;
		if (pos.equals("RP")) return true;
		return false;
	}

	/**
	 * Features on the words that appear between start (inclusive)
	 * and end (non-inclusive).
	 * */
	private void between(
			FeatureVector v,
			Refinements r,
			String tag,
			int start,
			int end,
			Sentence s) {
		if (start >= end)
			return;
		Counts<String> pos = new Counts<>();
		for (int i = start; i < end; i++) {
			LexicalUnit lu = s.getLemmaLU(i);
			pos.increment(lu.pos);
			if (canLexicalize(i, s))
				pos.increment(lu.getFullString());
		}
		for (Map.Entry<String, Integer> x : pos.entrySet())
			b(v, r, tag, "count(" + x.getKey() + ")=" + x.getValue());
	}
}
