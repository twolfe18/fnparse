package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class Describe {

	public static String span(Span s, Sentence sent) {
		StringBuilder sb = new StringBuilder();
		for(int i=s.start; i<s.end; i++) {
			if(i > s.start) sb.append(" ");
			sb.append(sent.getWord(i));
		}
		return sb.toString();
	}
	
	public static String sentence(Sentence s) {
		StringBuilder sb = new StringBuilder();
		for(int i=0; i<s.size(); i++) {
			if(i > 0) sb.append(" ");
			sb.append(s.getWord(i));
		}
		return sb.toString();
	}
	
	public static String frameInstance(FrameInstance fi) {
		StringBuilder sb = new StringBuilder();
		sb.append("FrameInstance of " + fi.getFrame().getName());
		sb.append(" triggered by " + Arrays.toString(fi.getSentence().getWordFor(fi.getTarget())) + ":");
		for(int i=0; i<fi.numArguments(); i++) {
			Span extent = fi.getArgument(i);
			if(extent == Span.nullSpan) continue;
			sb.append(String.format(" %s=\"%s\"", fi.getFrame().getRole(i), span(fi.getArgument(i), fi.getSentence())));
		}
		return sb.toString();
	}

	// sort by frame and position in sentence
	public static final Comparator<FrameInstance> fiComparator = new Comparator<FrameInstance>() {
		@Override
		public int compare(FrameInstance arg0, FrameInstance arg1) {
			int f = arg0.getFrame().getId() - arg1.getFrame().getId();
			if (f != 0) return f;
			int k = 1000;	// should be longer than a sentence
			return (k * arg0.getTarget().end + arg0.getTarget().start)
					- (k * arg1.getTarget().end + arg1.getTarget().start);
		}
	};

	public static String fnParse(FNParse p) {
		StringBuilder sb = new StringBuilder("FNParse");
		if (p.getId() != null && p.getId().length() > 0)
			sb.append(" " + p.getId());
		sb.append(": ");
		sb.append(sentence(p.getSentence()) + "\n");
		List<FrameInstance> fis = new ArrayList<>();
		fis.addAll(p.getFrameInstances());
		Collections.sort(fis, fiComparator);
		for(FrameInstance fi : fis)
			sb.append(frameInstance(fi) + "\n");
		return sb.toString();
	}
	
	public static String fnTagging(FNTagging p) {
		StringBuilder sb = new StringBuilder("FNTagging ");
		if (p.getId() != null && p.getId().length() > 0)
			sb.append(" " + p.getId());
		sb.append(": ");
		sb.append(sentence(p.getSentence()) + "\n");
		List<FrameInstance> fis = new ArrayList<>();
		fis.addAll(p.getFrameInstances());
		Collections.sort(fis, fiComparator);
		for(FrameInstance fi : fis)
			sb.append(frameInstance(fi) + "\n");
		return sb.toString();
	}

}
