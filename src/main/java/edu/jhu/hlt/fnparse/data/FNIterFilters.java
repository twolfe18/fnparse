package edu.jhu.hlt.fnparse.data;

import java.util.Iterator;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.util.HasSentence;

public class FNIterFilters {
	
	/**
	 * Find a particular FNTagging (or more generally HasSentence) by a sentence
	 * id.
	 * @param items
	 * @param sentenceId
	 * @return null if not found.
	 */
	public static <T extends HasSentence> T findBySentenceId(
			Iterator<T> items, String sentenceId) {
		while (items.hasNext()) {
			T it = items.next();
			if (sentenceId.equals(it.getSentence().getId()))
				return it;
		}
		return null;
	}

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
		private int totalSkipped = 0;
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
					totalSkipped++;
					//System.err.println("[SkipExceptions] bad FNTagging, " + totalSkipped + " total");
				}
			}
			return null;
		}

		@Override
		public boolean hasNext() {
			if(next == null && totalSkipped > 0)
				System.err.println("[SkipExceptions] skipped " + totalSkipped + " FNParses");
			return next != null;
		}

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
