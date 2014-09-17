package edu.jhu.hlt.fnparse.inference.heads;

import java.util.Arrays;

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
	
	private static boolean isQuote(int i, Sentence sent) {
		if ("''".equals(sent.getPos(i)))
			return true;
		return false;
	}

	@Override
	public int head(Span s, Sentence sent) {
		if (s == Span.nullSpan)
			throw new IllegalArgumentException();
		if (s.width() == 0)
			throw new IllegalArgumentException();
		if(s.width() == 1)
			return s.start;

		// Not in their paper
		// Strip off quotations if they're present
		if (isQuote(s.start, sent) && isQuote(s.end-1, sent))
			return head(Span.getSpan(s.start + 1, s.end - 1), sent);

		// Removes ambiguity
		if (s.width() > 1 && Arrays.asList("IN", "TO").contains(sent.getPos(s.start)))
			return head(Span.getSpan(s.start+1, s.end), sent);

		if(sent.getPos(s.start).startsWith("V"))
			return s.start;
		if (s.start+1 < s.end
				&& "TO".equals(sent.getPos(s.start))
				&& sent.getPos(s.start+1).startsWith("V"))
			return s.start+1;

		if(sent.getPos(s.start).startsWith("J"))
			return s.end - 1;

		// NOT IN THEIR PAPER: Recurse to the left of a possesive marker
		/*
		for (int i = s.start; i < s.end; i++) {
			if ("POS".equals(sent.getPos(i)))
				return head(Span.getSpan(s.start, i), sent);
		}
		*/

		for(int i=s.start+1; i<s.end; i++)
			if(sent.getWord(i).equalsIgnoreCase("of") && sent.getPos(i-1).startsWith("N"))
				return i-1;

		// Scan for external dependency
		for (int i = s.end - 1; i >= s.start; i--) {
			int p = sent.governor(i);
			if (!s.includes(p)) {
				if (i > s.start
						&& Arrays.asList("CC", "POS", "WDT", "TO", "IN")
						.contains(sent.getPos(i))) {
					return head(Span.getSpan(s.start, i), sent);
				} else {
					return i;
				}
			}
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
