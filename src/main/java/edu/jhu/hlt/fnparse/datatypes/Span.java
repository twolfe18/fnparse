package edu.jhu.hlt.fnparse.datatypes;

public class Span {

	public int start;	// inclusive
	public int end;		// non-inclusive

	public static final Span nullSpan = new Span();
	
	// intern instances of Span just like String
	// also, you can use ==
	private static int internedMaxSentSize = 30;
	private static Span[][] internedSpans = new Span[0][0];
	
	public static Span getSpan(int start, int end) {
		
		// make a bigger table if the previous was too small
		if(start >= internedSpans.length) {
			internedMaxSentSize = end + 10;
			
			// sanity check
			if(internedMaxSentSize > 100)
				throw new IllegalStateException("what are you doing with these huge sentences?");
			
			Span[][] newInternedSpans = new Span[internedMaxSentSize-1][];
			for(int s=0; s<internedMaxSentSize; s++) {
				newInternedSpans[s] = new Span[internedMaxSentSize - s - 1];
				for(int e=s+1; e<internedMaxSentSize; e++) {
					int ei = e - s - 1;
					// use old value if possible (needed to ensure == works)
					newInternedSpans[s][ei] =
							s < internedSpans.length && ei < internedSpans[s].length
								? internedSpans[s][ei]
								: new Span(s, e);
				}
			}
			internedSpans = newInternedSpans;
		}
		return internedSpans[start][end - start - 1];
	}
	
	/**
	 * @param start is inclusive, 0-indexed
	 * @param end is non-inclusive, 0-indexed
	 */
	private Span(int start, int end) {
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
	
	public static Span widthOne(int wordIdx) {
		return getSpan(wordIdx, wordIdx+1);
	}
}

