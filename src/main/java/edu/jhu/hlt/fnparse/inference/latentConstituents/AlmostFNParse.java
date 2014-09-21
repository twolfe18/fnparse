package edu.jhu.hlt.fnparse.inference.latentConstituents;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.util.Describe;

/**
 * This specifies a set of frames marked in text (FNTagging) as well as a pruned
 * set of spans that are allowable for every 
 * 
 * @author travis
 */
public class AlmostFNParse extends FNTagging {
	private Map<FrameInstance, List<Span>> possibleArgs; // irrespective of role

	public AlmostFNParse(
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

	public int numFrameInstance() {
		return numFrameInstances();
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

	public static List<AlmostFNParse> optimalPrune(List<FNParse> ps) {
		List<AlmostFNParse> prunes = new ArrayList<>();
		for (FNParse p : ps)
			prunes.add(optimalPrune(p));
		return prunes;
	}

	public String describe() {
		StringBuilder sb = new StringBuilder("<AlmostFNParse of ");
		sb.append(sent.getId());
		sb.append("\n");
		for (int i = 0; i < numFrameInstance(); i++) {
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
	 * @return an AlmostParse that represents the minimal set of arguments
	 * required to cover all of the arguments for each frame instance in the
	 * given parse. The set of possible argument spans for each (frame,target,role)
	 * will contain Span.nullSpan.
	 */
	public static AlmostFNParse optimalPrune(FNParse p) {
		Map<FrameInstance, List<Span>> possibleArgs = new HashMap<>();
		for (FrameInstance fi : p.getFrameInstances()) {
			Set<Span> args = new HashSet<>();
			for (int k = 0; k < fi.getFrame().numRoles(); k++)
				args.add(fi.getArgument(k));
			args.remove(Span.nullSpan);
			List<Span> argsList = new ArrayList<>();
			argsList.addAll(args);
			argsList.add(Span.nullSpan);
			FrameInstance key = FrameInstance.frameMention(
					fi.getFrame(), fi.getTarget(), fi.getSentence());
			List<Span> old = possibleArgs.put(key, argsList);
			if (old != null)
				throw new RuntimeException();
		}
		return new AlmostFNParse(
				p.getSentence(), p.getFrameInstances(), possibleArgs);
	}
}
