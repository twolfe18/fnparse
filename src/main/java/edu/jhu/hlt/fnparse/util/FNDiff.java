package edu.jhu.hlt.fnparse.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class FNDiff {

	public static String diffArgs(FNParse a, FNParse b, boolean printSame) {
		if (!a.getSentence().getId().equals(b.getSentence().getId()))
			throw new IllegalArgumentException();

		Set<String> aroles = new HashSet<>();
		addArgs(a, aroles);
		Set<String> broles = new HashSet<>();
		addArgs(b, broles);
		Set<String> allroles = new HashSet<>();
		allroles.addAll(aroles);
		allroles.addAll(broles);

		// In a but not b
		Set<String> aNotB = new HashSet<>();
		aNotB.addAll(aroles);
		aNotB.removeAll(broles);

		// In b but not a
		Set<String> bNotA = new HashSet<>();
		bNotA.addAll(broles);
		bNotA.removeAll(aroles);

		StringBuilder sb = new StringBuilder();
		for (String s : allroles) {
			if (aNotB.contains(s)) {
				sb.append("- ");
			} else if (bNotA.contains(s)) {
				sb.append("+ ");
			} else {
				if (printSame) sb.append(" ");
				else continue;
			}
			sb.append(s);
			sb.append("\n");
		}

		return sb.toString();
	}

	private static void addArgs(FNParse p, Collection<String> addTo) {
		Sentence s = p.getSentence();
		for (FrameInstance fi : p.getFrameInstances()) {
			String target = Arrays.toString(s.getWordFor(fi.getTarget()));
			for (int k = 0; k < fi.getFrame().numRoles(); k++) {
				Span arg = fi.getArgument(k);
				if (arg == Span.nullSpan) continue;
				addTo.add(String.format("frame=%s target=%s role(%s)=%s",
						fi.getFrame().getName(),
						target,
						fi.getFrame().getRole(k),
						Arrays.toString(s.getWordFor(arg))));
			}
		}
	}
	
	public static String diffFrames(
			FNTagging a, FNTagging b, boolean printSame) {
		Set<FrameInstance> all = new HashSet<>();
		all.addAll(a.getFrameInstances());
		all.addAll(b.getFrameInstances());
		Set<FrameInstance> added = inSecondNotFirst(a, b);
		Set<FrameInstance> removed = inFirstNotSecond(a, b);
		StringBuilder sb = new StringBuilder();
		for (FrameInstance fi : all) {
			if (added.contains(fi)) {
				sb.append("+ ");
				sb.append(Describe.frameInstance(fi));
				sb.append("\n");
			} else if (removed.contains(fi)) {
				sb.append("- ");
				sb.append(Describe.frameInstance(fi));
				sb.append("\n");
			} else if (printSame) {
				sb.append("  ");
				sb.append(Describe.frameInstance(fi));
				sb.append("\n");
			}
		}
		return sb.toString();
	}

	private static Set<FrameInstance> inFirstNotSecond(
			FNTagging a, FNTagging b) {
		Set<FrameInstance> s = new HashSet<>();
		s.addAll(a.getFrameInstances());
		s.removeAll(b.getFrameInstances());
		return s;
	}

	private static Set<FrameInstance> inSecondNotFirst(
			FNTagging a, FNTagging b) {
		Set<FrameInstance> s = new HashSet<>();
		s.addAll(b.getFrameInstances());
		s.removeAll(a.getFrameInstances());
		return s;
	}

}
