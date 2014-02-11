package edu.jhu.hlt.fnparse.util;

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
		sb.append("FrameInstance of " + fi.getFrame().getName() + ":");
		for(int i=0; i<fi.numArguments(); i++) {
			Span extent = fi.getArgument(i);
			if(extent == Span.nullSpan) continue;
			sb.append(String.format(" %s=\"%s\"", fi.getFrame().getRole(i), span(fi.getArgument(i), fi.getSentence())));
		}
		return sb.toString();
	}
	
	public static String fnParse(FNParse p) {
		StringBuilder sb = new StringBuilder("FNParse: ");
		sb.append(sentence(p.getSentence()) + "\n");
		for(FrameInstance fi : p.getFrameInstances())
			sb.append(frameInstance(fi) + "\n");
		return sb.toString();
	}
	
	public static String fnTagging(FNTagging p) {
		StringBuilder sb = new StringBuilder("FNTagging: ");
		sb.append(sentence(p.getSentence()) + "\n");
		for(FrameInstance fi : p.getFrameInstances())
			sb.append(frameInstance(fi) + "\n");
		return sb.toString();
	}

}
