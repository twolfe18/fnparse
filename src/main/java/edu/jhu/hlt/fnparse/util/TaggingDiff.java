package edu.jhu.hlt.fnparse.util;

import java.util.HashSet;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;

public class TaggingDiff {
	
	public static String diff(FNTagging a, FNTagging b, boolean printSame) {
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

	public static Set<FrameInstance> inFirstNotSecond(FNTagging a, FNTagging b) {
		Set<FrameInstance> s = new HashSet<>();
		s.addAll(a.getFrameInstances());
		s.removeAll(b.getFrameInstances());
		return s;
	}

	public static Set<FrameInstance> inSecondNotFirst(FNTagging a, FNTagging b) {
		Set<FrameInstance> s = new HashSet<>();
		s.addAll(b.getFrameInstances());
		s.removeAll(a.getFrameInstances());
		return s;
	}

}
