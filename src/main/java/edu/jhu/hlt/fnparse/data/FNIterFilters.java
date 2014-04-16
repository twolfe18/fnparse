package edu.jhu.hlt.fnparse.data;

import java.util.*;

import edu.jhu.hlt.fnparse.datatypes.*;

public class FNIterFilters {

	public static final class OnlyParses implements Iterator<FNParse> {
		
		private Iterator<FNTagging> iter;
		private FNParse next;
		
		public OnlyParses(Iterator<FNTagging> iter) {
			this.iter = iter;
			this.next = findNext();
		}
		
		private FNParse findNext() {
			while(iter.hasNext()) {
				FNTagging n = iter.next();
				if(n instanceof FNParse)
					return (FNParse) n;
			}
			return null;
		}

		@Override
		public boolean hasNext() { return next != null; }

		@Override
		public FNParse next() {
			FNParse ret = next;
			next = findNext();
			return ret;
		}

		@Override
		public void remove() { throw new UnsupportedOperationException(); }
		
	}
	
	public static final class OnlyTaggings implements Iterator<FNTagging> {

		private Iterator<FNTagging> iter;
		private FNTagging next;
		
		public OnlyTaggings(Iterator<FNTagging> iter) {
			this.iter = iter;
			this.next = findNext();
		}
		
		private FNTagging findNext() {
			while(iter.hasNext()) {
				FNTagging n = iter.next();
				if(!(n instanceof FNParse))
					return n;
			}
			return null;
		}

		
		@Override
		public boolean hasNext() { return next != null; }

		@Override
		public FNTagging next() {
			FNTagging ret = next;
			next = findNext();
			return ret;
		}

		@Override
		public void remove() { throw new UnsupportedOperationException(); }
		
	}
	
	public static final class SkipExceptions implements Iterator<FNTagging> {
		
		private Iterator<FNTagging> iter;
		private FNTagging next;
		
		public SkipExceptions(Iterator<FNTagging> iter) {
			this.iter = iter;
			this.next = findNext();
		}
		
		private FNTagging findNext() {
			while(iter.hasNext()) {
				try {
					FNTagging n = iter.next();
					return n;
				}
				catch(IllegalArgumentException iae) {
					System.err.println("[SkipExceptions] bad FNTagging!");
				}
			}
			return null;
		}

		
		@Override
		public boolean hasNext() { return next != null; }

		@Override
		public FNTagging next() {
			FNTagging ret = next;
			next = findNext();
			return ret;
		}

		@Override
		public void remove() { throw new UnsupportedOperationException(); }
	}
}
