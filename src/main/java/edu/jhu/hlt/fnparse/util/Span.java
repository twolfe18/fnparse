package edu.jhu.hlt.fnparse.util;

public class Span {

	public int start;	// inclusive
	public int end;		// non-inclusive

	public Span(int start, int end) {
		assert end > start;
		assert start >= 0;
		this.start = start;
		this.end = end;
	}

	public int width() { return end - start; }
	
	public int hashCode() { return (start<<16) | end; }
	
	public boolean equals(Object other) {
		if(other instanceof Span) {
			Span s = (Span) other;
			return start == s.start && end == s.end;
		}
		return false;
	}
	
	public static final Span NULL_SPAN = new Span(0, 0);
}

