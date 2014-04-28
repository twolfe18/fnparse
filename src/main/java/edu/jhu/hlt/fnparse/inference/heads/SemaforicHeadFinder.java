package edu.jhu.hlt.fnparse.inference.heads;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * footnote 9 of https://www.ark.cs.cmu.edu/SEMAFOR/das+schneider+chen+smith.tr10.pdf
 * "If the target is not a subtree in the parse, we consider the words that have parents outside the span, and
 *  apply three heuristic rules to select the head: 1) choose the first word if it is a verb; 2) choose the last word
 *  if the first word is an adjective; 3) if the target contains the word of, and the first word is a noun, we choose it.
 *  If none of these hold, choose the last word with an external parent to be the head."
 * 
 * @author travis
 */
public class SemaforicHeadFinder implements HeadFinder {

	private static final long serialVersionUID = 1L;

	@Override
	public int head(Span s, Sentence sent) {
		
		assert s != Span.nullSpan;

		if(s.width() == 1)
			return s.start;
		
		if(sent.getPos(s.start).startsWith("V"))
			return s.start;
		
		if(sent.getPos(s.start).startsWith("J"))
			return s.end - 1;
		
		for(int i=s.start+1; i<s.end; i++)
			if(sent.getWord(i).equalsIgnoreCase("of") && sent.getPos(i-1).startsWith("N"))
				return i-1;
		
		for(int i=s.end-1; i>=s.start; i--) {
			int p = sent.governor(i);
			if(p < s.start || p >= s.end)
				return i;
		}
		
		// BELOW NOT IN THEIR PAPER:
		// collapsed dependencies might lead to "incest" (no parent outside this span)
		
		// choose the first verb
		for(int i=s.start; i<s.end; i++) {
			if(sent.getPos(i).startsWith("V"))
				return i;
		}
		
		// choose the last word in the first sequence of nouns?
		boolean inNP = false;
		for(int i=s.start; i<s.end; i++) {
			String p = sent.getPos(i);
			inNP |= p.startsWith("N") || p.endsWith("DT");
			if(inNP && (i == s.end-1 || !sent.getPos(i+1).startsWith("N")))
				return i;
		}

		throw new IllegalStateException("how?");
	}

}
