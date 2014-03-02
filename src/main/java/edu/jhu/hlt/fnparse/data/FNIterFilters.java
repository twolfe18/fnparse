package edu.jhu.hlt.fnparse.data;

import java.util.*;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider.FIIterator;
import edu.jhu.hlt.fnparse.datatypes.*;

public class FNIterFilters {

	public static final class OnlyParses implements Iterator<FNParse> {
		
		private FIIterator iter;
		private FNParse next;
		
		public OnlyParses(FIIterator iter) {
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

		private FIIterator iter;
		private FNTagging next;
		
		public OnlyTaggings(FIIterator iter) {
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
}
