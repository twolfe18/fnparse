package edu.jhu.hlt.fnparse.data;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

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

  public static class SkipSentences<T extends FNTagging>
  extends CustomPredicate<T> {
    private Set<String> skipSentenceIds = new HashSet<>();
    public SkipSentences(
        Iterator<T> iter,
        Collection<String> skipSentenceIds) {
      super(iter);
      this.skipSentenceIds.addAll(skipSentenceIds);
    }
    @Override
    public boolean keep(T cur) {
      if (cur == null) {
        throw new RuntimeException();
      }
      return !skipSentenceIds.contains(cur.getSentence().getId());
    }
  }

  public static abstract class CustomPredicate<T extends FNTagging>
  implements Iterator<T> {
    private Iterator<T> iter;
    private T next;
    private boolean init = false;

    public CustomPredicate(Iterator<T> iter) {
      this.iter = iter;
      //this.next = findNext();
    }

    /** Return true if this FNTagging should appear in output */
    public abstract boolean keep(T cur);

    private void init() {
      assert !init;
      assert next == null;
      this.next = findNext();
      init = true;
    }

    private T findNext() {
      while (iter.hasNext()) {
        T n = iter.next();
        if (keep(n))
          return n;
      }
      return null;
    }

    @Override
    public boolean hasNext() {
      if (!init) init();
      return next != null;
    }

    @Override
    public T next() {
      T ret = next;
      next = findNext();
      return ret;
    }

    @Override
    public void remove() { throw new UnsupportedOperationException(); }
  }

  public static final class OnlyParses<T extends FNTagging>
  extends CustomPredicate<T> {
    public OnlyParses(Iterator<T> iter) {
      super(iter);
    }
    @Override
    public boolean keep(T cur) {
      return cur instanceof FNParse;
    }
  }

  public static final class OnlyTaggings<T extends FNTagging>
  extends CustomPredicate<T> {
    public OnlyTaggings(Iterator<T> iter) {
      super(iter);
    }
    @Override
    public boolean keep(T cur) {
      return !(cur instanceof FNParse);
    }
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
