package edu.jhu.hlt.fnparse.datatypes;

import static edu.jhu.hlt.fnparse.util.ScalaLike.require;

import java.util.Iterator;

public class Expansion {

	/**
	 * represents a whole bunch of Expansions implicitly.
	 * even though it implements iterator, you can call size() and get(i) in O(1)
	 * 
	 * now that i've added get and size in O(1),
	 * there is really no reason for this to be an iterator
	 * (where you need to worry about the state, reset, etc).
	 * 
	 * @author travis
	 */
	public static class Iter implements Iterator<Expansion> {
		
		private int expLeft, expRight;
		private int maxExpLeft, maxExpRight;
		
		public Iter(int head, int sentLen) {
			this.expLeft = 0;
			this.expRight = 0;
			this.maxExpLeft = head;
			this.maxExpRight = sentLen - head - 1;	// n=2, head=1 => maxExpRight=0
		}
	
		public Iter(int head, int sentLen, int maxExpLeft, int maxExpRight) {
			this.expLeft = 0;
			this.expRight = 0;
			this.maxExpLeft = head;
			this.maxExpRight = sentLen - head - 1;	// n=2, head=1 => maxExpRight=0
			if(maxExpLeft < this.maxExpLeft)
				this.maxExpLeft = maxExpLeft;
			if(maxExpRight < this.maxExpRight)
				this.maxExpRight = maxExpRight;
		}
		
		/**
		 * resets the iterator functionality.
		 * @deprecated
		 */
		public void reset() {
			this.expLeft = 0;
			this.expRight = 0;
		}
	
		public int size() {
			return (maxExpLeft+1) * (maxExpRight+1);
		}
		
		public Expansion get(int i) {
			int m = maxExpRight + 1;	// outer loop, +1 for <=
			int el = i / m;
			int er = i % m;
			return new Expansion(el, er);
		}
		
		public int indexOf(Expansion needle) {
			int n = size();
			for(int i=0; i<n; i++) {
				Expansion e = get(i);
				if(needle.equals(e))
					return i;
			}
			return -1;
		}
		
		/**
		 * @deprecated
		 */
		@Override
		public boolean hasNext() {
			return expLeft <= maxExpLeft && expRight <= maxExpRight;
		}
		
		// this is what I'm mimicking:
		//for(int l=0; l<=maxExpLeft; l++)
		//	for(int r=0; r<=maxExpRight; r++)
		//		yield new Expansion(l, r)
	
		/**
		 * @deprecated
		 */
		@Override
		public Expansion next() {
			Expansion e = new Expansion(expLeft, expRight);
			expRight++;
			if(expRight == maxExpRight+1) {
				expRight = 0;
				expLeft++;
			}
			return e;
		}
	
		/**
		 * @deprecated
		 */
		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}	
	}
	
	public static final Expansion noExpansion = new Expansion(0, 0);

	private int expLeft, expRight;
	
	// TODO add memoization like Span
	
	public Expansion(int left, int right) {
		require(left >= 0);
		require(right >= 0);
		expLeft = left;
		expRight = right;
	}
	
	@Override
	public int hashCode() { return (expLeft << 16) | expRight; }
	
	@Override
	public boolean equals(Object other) {
		if(other instanceof Expansion) {
			Expansion e  = (Expansion) other;
			return expLeft == e.expLeft && expRight == e.expRight;
		}
		else return false;
	}
	
	@Override
	public String toString() {
		return String.format("<Expansion %d-%d>", expLeft, expRight);
	}
	
	public Span upon(Span s) {
		int l = s.start - expLeft;
		int r = s.end + expRight;
		return Span.getSpan(l, r);
	}
	
	public Span upon(int headIdx) {
		return upon(headIdx, expLeft, expRight);			
	}
	
	public static Span upon(int headIdx, int expLeft, int expRight) {
		int l = headIdx - expLeft;
		int r = headIdx+1 + expRight;
		return Span.getSpan(l, r);
	}
	
	public static Expansion headToSpan(int headIdx, Span sp) {
		if(!sp.includes(headIdx))
			throw new IllegalArgumentException();
		int el = headIdx - sp.start;
		int er = sp.end - (headIdx+1);
		return new Expansion(el, er);
	}
}

