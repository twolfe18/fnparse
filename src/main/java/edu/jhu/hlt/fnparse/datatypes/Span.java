package edu.jhu.hlt.fnparse.datatypes;

public final class Span implements Comparable<Span> {

  // TODO Use this for Span[] vs Span[][]
  /** Densely embeds all spans into the natural numbers */
  public static int index(Span s) {
    /*
key = (i,j) where i<j
M = number of mentions

There are two arrangements: {locality in i, locality in j}
(locality in j is preffered)
and two orderings: {small blocks in front, big blocks in front}.
(small blocks in front is preferred due to easier math)

<none s.t. j=0>
(i=0, j=1)     // 1 of these
(i=0, j=2)
(i=1, j=2)     // 2 of these
(i=0, j=3)
(i=1, j=3)
(i=2, j=3)     // 3 of these
...

index(i,j) = Z(j) + i
where Z(j) = sum_{t=1}^{j-1} t = i*(i-1)/2
(Note: related formula for the sum of the first k natural numbers
is k*(k+1)/2, this is the first k-1 numbers, hence (k-1)*k/2)

index(i=2,j=4) = Z(4) + 2
Z(4) = 4*3/2 = 6
=> 8, correct!
     */
    int Z = s.end * (s.end - 1) / 2;
    return Z + s.start;
  }

  public int start;  // inclusive
  public int end;    // non-inclusive

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

    if(start >= end) {
      throw new IllegalArgumentException(
          "start must be less than end: " + start + " >= " + end);
    }

    // make a bigger table if the previous was too small
    if(end > internedSpans.length) {
      int newInternedMaxSentSize = end + 10;

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

  public boolean covers(Span other) {
    return this.start <= other.start && other.end <= this.end;
  }

  public boolean crosses(Span other) {
    /*
    boolean sharesEndpoint = start == other.start || end == other.end;
    if (sharesEndpoint)
      return false;
    boolean nested1 = start <= other.start && other.end <= end;
    if (nested1)
      return false;
    boolean nested2 = other.start <= start && end <= other.end;
    if (nested2)
      return false;
    return overlaps(other);
    */
    Span a = this;
    Span b = other;
    if (a.start < b.start && b.start < a.end && a.end < b.end)
      return true;
    a = other;
    b = this;
    if (a.start < b.start && b.start < a.end && a.end < b.end)
      return true;
    return false;
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

  public int hashCode() {
    int w = end - start;
    return (w << 16) ^ start;
  }

  /** Puts a hash in the lower 16 bits of the returned int (may overflow) */
  public int hashCode16() {
    int w = end - start;
    return (w << 9) ^ start;
  }

  public String shortString() {
    return start + "-" + end;
  }

  public boolean equals(int start, int end) {
    return start == this.start && end == this.end;
  }

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

  @Override
  public int compareTo(Span o) {
    int c1 = end - o.end;
    if (c1 != 0) return c1;
    return start - o.start;
  }
}

