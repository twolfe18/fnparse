package edu.jhu.hlt.fnparse.util;

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
}
