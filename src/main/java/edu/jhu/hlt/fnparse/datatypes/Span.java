package edu.jhu.hlt.fnparse.datatypes;

public class Span {

	public int start;	// inclusive
	public int end;		// non-inclusive

	/**
	 * @param start is inclusive, 0-indexed
	 * @param end is non-inclusive, 0-indexed
	 */
	public Span(int start, int end) {
		assert end > start;
		assert start >= 0;
		this.start = start;
		this.end = end;
	}
	
	/** creates the null span, private so that it can only be done once here statically */
	private Span() {
		this.start = 0;
		this.end = 0;
	}

	public int width() { return end - start; }
	
	/**
	 * return true if this span is to the left of
	 * other with no overlap.
	 */
	public boolean before(Span other) {
		return this.end <= other.start;
	}
	
	/**
	 * return true if this span is to the right of
	 * other with no overlap.
	 */
	public boolean after(Span other) {
		return this.start >= other.end;
	}
	
	public boolean overlaps(Span other) {
		if(end <= other.start) return false;
		if(start >= other.end) return false;
		return true;
	}
	
	public String toString() {
		return String.format("<Span %d-%d>", start, end);
	}
	
	public int hashCode() { return (start<<16) | end; }
	
	public boolean equals(Object other) {
		if(other instanceof Span) {
			Span s = (Span) other;
			return start == s.start && end == s.end;
		}
		return false;
	}
	
	public static final Span nullSpan = new Span();
	
	public static Span widthOne(int wordIdx) {
		return new Span(wordIdx, wordIdx+1);
	}
}

