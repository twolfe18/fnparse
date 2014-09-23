package edu.jhu.hlt.fnparse.inference.latentConstituents;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.FPR;
import edu.jhu.hlt.fnparse.util.Describe;

/**
 * This specifies a set of frames marked in text (FNTagging) as well as a pruned
 * set of spans that are allowable for every frame.
 * 
 * @author travis
 */
public class FNParseSpanPruning extends FNTagging {
	// Irrespective of role.
	// Every key should be a FrameInstance.frameMention
	private Map<FrameInstance, List<Span>> possibleArgs;

	public FNParseSpanPruning(
			Sentence s,
			List<FrameInstance> frameMentions,
			Map<FrameInstance, List<Span>> possibleArgs) {
		super(s, frameMentions);
		this.possibleArgs = possibleArgs;
		for (FrameInstance fi : frameMentions) {
			// fi may have arguments, whereas the keys in possibleArgs will not,
			// and will only represent a (frame,target) pair.
			FrameInstance key = FrameInstance.frameMention(
					fi.getFrame(), fi.getTarget(), sent);
			if (!possibleArgs.containsKey(key))
				assert false;
		}
	}

	/** Returns the number of span variables that are permitted by this mask */
	public int numPossibleArgs() {
		int numPossibleArgs = 0;
		for (Map.Entry<FrameInstance, List<Span>> x : possibleArgs.entrySet()) {
			Frame f = x.getKey().getFrame();
			List<Span> l = x.getValue();
			numPossibleArgs += f.numRoles() * l.size();
		}
		return numPossibleArgs;
	}

	/** Returns the number of span variables possible without any pruning */
	public int numPossibleArgsNaive() {
		int n = sent.size();
		int numPossibleSpans = (n*(n-1))/2 + n + 1;
		int numPossibleRoles = 0;
		for (FrameInstance fi : this.frameInstances)
			numPossibleRoles += fi.getFrame().numRoles();
		int numPossibleArgs = numPossibleRoles * numPossibleSpans;
		return numPossibleArgs;
	}

	public Frame getFrame(int frameInstanceIndex) {
		return getFrameInstance(frameInstanceIndex).getFrame();
	}

	public Span getTarget(int frameInstanceIndex) {
		return getFrameInstance(frameInstanceIndex).getTarget();
	}

	public List<Span> getPossibleArgs(int frameInstanceIndex) {
		FrameInstance fi = getFrameInstance(frameInstanceIndex);
		FrameInstance key = FrameInstance.frameMention(
				fi.getFrame(), fi.getTarget(), fi.getSentence());
		List<Span> args = possibleArgs.get(key);
		if (args == null)
			throw new IllegalStateException();
		return args;
	}

	/** Returns the i^{th} (frame,target) */
	public FrameInstance getFrameTarget(int i) {
		FrameInstance fi = frameInstances.get(i);
		return FrameInstance.frameMention(fi.getFrame(), fi.getTarget(), fi.getSentence());
	}

	/** Returns the i^{th} (frame,target,args) where args may be null */
	public FrameInstance getFrameTargetWithArgs(int i) {
		return frameInstances.get(i);
	}

	public String describe() {
		StringBuilder sb = new StringBuilder("<AlmostFNParse of ");
		sb.append(sent.getId());
		sb.append("\n");
		for (int i = 0; i < numFrameInstances(); i++) {
			FrameInstance fi = getFrameTarget(i);
			sb.append(Describe.frameInstance(fi));
			Collection<Span> keep = possibleArgs.get(fi);
			if (keep == null) {
				sb.append(" NULL LIST OF SPANS\n");
			} else if (keep.size() == 0) {
				sb.append(" NO SPANS POSSIBLE\n");
			} else {
				for (Span s : keep)
					sb.append(String.format(" %d-%d", s.start, s.end));
				sb.append("\n");
			}
		}
		return sb.toString();
	}

	/**
	 * @return The recall attainable by this pruning mask if it were applied
	 * during inference on the gold (frame,target)s in the given parse.
	 */
	public FPR recall(FNParse p) {
		boolean macro = false;
		FPR fpr = new FPR(macro);
		for (FrameInstance fi : p.getFrameInstances()) {
			FrameInstance key = FrameInstance.frameMention(
					fi.getFrame(), fi.getTarget(), fi.getSentence());
			Set<Span> possibleS = new HashSet<>();
			List<Span> possible = possibleArgs.get(key);
			if (possible != null) possibleS.addAll(possible);
			Frame f = fi.getFrame();
			for (int k = 0; k < f.numRoles(); k++) {
				Span s = fi.getArgument(k);
				if (s == Span.nullSpan)
					continue;
				else if (possibleS.contains(s))
					fpr.accumTP();
				else
					fpr.accumFN();
			}
		}
		return fpr;
	}

	public static List<FNParseSpanPruning> noisyPruningOf(
			List<FNParse> parses,
			double pIncludeNegativeSpan,
			Random r) {
		List<FNParseSpanPruning> out = new ArrayList<>();
		for (FNParse p : parses)
			out.add(noisyPruningOf(p, pIncludeNegativeSpan, r));
		return out;
	}

	/**
	 * Creates a pruning that includes all of the spans in the given parse, plus
	 * some randomly included others. Every span that is not used in the given
	 * parse is included with probability pIncludeNegativeSpan. Span.nullSpan is
	 * also always included as an allowable span in the pruning.
	 */
	public static FNParseSpanPruning noisyPruningOf(
			FNParse p,
			double pIncludeNegativeSpan,
			Random r) {
		Map<FrameInstance, List<Span>> possibleArgs = new HashMap<>();
		int n = p.getSentence().size();
		for (FrameInstance fi : p.getFrameInstances()) {
			Set<Span> args = new HashSet<>();
			for (int k = 0; k < fi.getFrame().numRoles(); k++)
				args.add(fi.getArgument(k));
			args.add(Span.nullSpan);
			for (int start = 0; start < n; start++)
				for (int end = start + 1; end <= n; end++)
					if (r.nextDouble() < pIncludeNegativeSpan)
						args.add(Span.getSpan(start, end));
			List<Span> argsList = new ArrayList<>();
			argsList.addAll(args);
			FrameInstance key = FrameInstance.frameMention(
					fi.getFrame(), fi.getTarget(), fi.getSentence());
			List<Span> old = possibleArgs.put(key, argsList);
			if (old != null)
				throw new RuntimeException();
		}
		return new FNParseSpanPruning(
				p.getSentence(), p.getFrameInstances(), possibleArgs);
	}

	public static List<FNParseSpanPruning> optimalPrune(List<FNParse> ps) {
		List<FNParseSpanPruning> prunes = new ArrayList<>();
		for (FNParse p : ps)
			prunes.add(optimalPrune(p));
		return prunes;
	}

	/**
	 * @return an AlmostParse that represents the minimal set of arguments
	 * required to cover all of the arguments for each frame instance in the
	 * given parse. The set of possible argument spans for each (frame,target,role)
	 * will contain Span.nullSpan.
	 */
	public static FNParseSpanPruning optimalPrune(FNParse p) {
		Map<FrameInstance, List<Span>> possibleArgs = new HashMap<>();
		for (FrameInstance fi : p.getFrameInstances()) {
			Set<Span> args = new HashSet<>();
			for (int k = 0; k < fi.getFrame().numRoles(); k++)
				args.add(fi.getArgument(k));
			args.add(Span.nullSpan);
			List<Span> argsList = new ArrayList<>();
			argsList.addAll(args);
			FrameInstance key = FrameInstance.frameMention(
					fi.getFrame(), fi.getTarget(), fi.getSentence());
			List<Span> old = possibleArgs.put(key, argsList);
			if (old != null)
				throw new RuntimeException();
		}
		return new FNParseSpanPruning(
				p.getSentence(), p.getFrameInstances(), possibleArgs);
	}
}
