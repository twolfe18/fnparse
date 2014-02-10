package edu.jhu.hlt.fnparse.datatypes;

public class Span {

	public int start;	// inclusive
	public int end;		// non-inclusive

	public static final Span nullSpan = new Span(0, 0);
	
	// intern instances of Span just like String
	// also, you can use ==
	// this table is indexed as [start][width - 1]
	private static Span[][] internedSpans = new Span[0][0];
	
	public static Span getSpan(int start, int end) {
		
		// don't store this in the table, because all other spans will
		// obey the invariant start > end (i.e. width >= 1).
		if(start == 0 && end == 0)
			return nullSpan;
		
		// make a bigger table if the previous was too small
		if(end > internedSpans.length) {
			int newInternedMaxSentSize = end + 10;
			
			// sanity check
			if(newInternedMaxSentSize > 200)
				throw new IllegalStateException("what are you doing with these huge sentences?");
			
			Span[][] newInternedSpans = new Span[newInternedMaxSentSize][];
			for(int s=0; s<newInternedSpans.length; s++) {
				newInternedSpans[s] = new Span[newInternedMaxSentSize - s];
				for(int width=1; width <= newInternedMaxSentSize - s; width++) {
					// use old value if possible (needed to ensure == works)
					newInternedSpans[s][width - 1] =
							s < internedSpans.length && width-1 < internedSpans[s].length
								? internedSpans[s][width-1]
								: new Span(s, s+width);
				}
			}
			internedSpans = newInternedSpans;
		}
		int width = end - start;
		return internedSpans[start][width - 1];
	}
	
	private Span(int start, int end) {
		this.start = start;
		this.end = end;
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
	
	public boolean includes(int wordIdx) {
		return start <= wordIdx && wordIdx < end;
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

