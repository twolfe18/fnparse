package edu.jhu.hlt.fnparse.rl;

import java.util.Arrays;
import java.util.Collection;

import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.util.HasSpan;

/**
 * A persistent data structure for storing things (e.g. Actions) that have a
 * Span by various Span-related properties.
 */
public class SpanIndex<T extends HasSpan> {

  // Linked list of Actions
  public static class IndexItem<A extends HasSpan> {
    public final A payload;
    public final IndexItem<A> prevNonEmptyItem;  // next node
    public final int size;                    // how many items in this linked list, including this payload
    public IndexItem(A a, IndexItem<A> prev) {
      assert a != null;
      this.payload = a;
      this.prevNonEmptyItem = prev;
      if (prev != null)
        this.size = prev.size + 1;
      else
        this.size = 1;
    }
    @Override
    public String toString() {
      return payload + " -> " + prevNonEmptyItem;
    }
  }

  // These are all indexed by word
  private IndexItem<T>[] coversToken;
  private IndexItem<T>[] startsAt;
  private IndexItem<T>[] endsAt;
  private IndexItem<T> all;

  @SuppressWarnings("unchecked")
  public SpanIndex(int sentenceLength) {
    this.all = null;
    this.coversToken = new IndexItem[sentenceLength];
    this.startsAt = new IndexItem[sentenceLength];
    this.endsAt = new IndexItem[sentenceLength];
  }

  private SpanIndex(IndexItem<T> all, IndexItem<T>[] coversToken, IndexItem<T>[] startsAt, IndexItem<T>[] endsAt) {
    this.all = all;
    this.coversToken = coversToken;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
  }

  public int getSentenceSize() {
    assert coversToken.length == startsAt.length;
    assert startsAt.length == endsAt.length;
    return coversToken.length;
  }

  public String toString() {
    int n = coversToken.length;
    StringBuilder sb = new StringBuilder("<ActionIndex " + n + "\n");
    for (int i = 0; i < n; i++)
      sb.append("covers[" + i + "] " + coversToken[i] + "\n");
    for (int i = 0; i < n; i++)
      sb.append("startsAt[" + i + "] " + startsAt[i] + "\n");
    for (int i = 0; i < n; i++)
      sb.append("endsAt[" + i + "] " + endsAt[i] + "\n");
    sb.append(">");
    return sb.toString();
  }

  /**
   * Adds all actions that have a span that covers the given span to the
   * given collection of Actions.
   * @return the collection passed in.
   */
  public <C extends Collection<T>> C crosses(Span s, C addTo) {
    return crosses(s.start, s.end, addTo);
  }
  public <C extends Collection<T>> C crosses(int sstart, int send, C addTo) {
    // Take all actions that appear in exactly one of:
    // actions that cross the first token and actions that cross the last token.
    // Below, implemented as all of the actions that cross the first token but
    // not the last.
    
    // (covers start XOR covers end) AND doesn't share an endpoint
    
    // This does not double count: the first loop will only take a span if it
    // doesn't cover end (and thus must start), and the second loop will only
    // take a span if it doesn't covers start (and thus must end).
    for (IndexItem<T> si = coversToken[sstart]; si != null; si = si.prevNonEmptyItem) {
      T a = si.payload;
      Span aSpan = a.getSpan();
      boolean coversEnd = aSpan.start <= send-1 && send-1 < aSpan.end;
      if (coversEnd) continue;
      boolean shareEndpoint = sstart == aSpan.start || send == aSpan.end;
      if (shareEndpoint) continue;
      addTo.add(a);
    }
    for (IndexItem<T> ei = coversToken[send - 1]; ei != null; ei = ei.prevNonEmptyItem) {
      T a = ei.payload;
      Span aSpan = a.getSpan();
      boolean coversStart = aSpan.start <= sstart && sstart < aSpan.end;
      if (coversStart) continue;
      boolean shareEndpoint = sstart == aSpan.start || send == aSpan.end;
      if (shareEndpoint) continue;
      addTo.add(a);
    }
    return addTo;
  }

  /**
   * Finds all Actions that have a span inside of (token subset) container.
   */
  public <C extends Collection<T>> C containedIn(Span container, C addTo) {
    if (container.end > startsAt.length) {
      throw new IllegalArgumentException("this container does not fit within "
          + "this index, using wrong index?");
    }
    for (int i = container.start; i < container.end; i++) {
      for (IndexItem<T> ii = startsAt[i]; ii != null; ii = ii.prevNonEmptyItem) {
        Span s = ii.payload.getSpan();
        assert s.start == i;
        if (s.end <= container.end)
          addTo.add(ii.payload);
      }
    }
    return addTo;
  }

  public IndexItem<T> allActions() {
    return all;
  }

  /**
   * Returns a linked list of Actions such that their first token was i
   * (i.e. span.start == i).
   * May return null if there are no such Actions.
   */
  public IndexItem<T> startsAt(int i) {
    return startsAt[i];
  }

  /**
   * Returns a linked list of Actions such that their last token was i
   * (NOTE this is not that span.end == i, but rather span.end-1 == i because
   *  Spans are exclusive in end).
   * May return null if there are no such Actions.
   */
  public IndexItem<T> endsAt(int i) {
    return endsAt[i];
  }

  public void mutableUpdate(T a) {
    all = new IndexItem<>(a, all);
    Span s = a.getSpan();
    for (int i = s.start; i < s.end; i++)
      coversToken[i] = new IndexItem<>(a, coversToken[i]);
    startsAt[s.start] = new IndexItem<>(a, startsAt[s.start]);
    endsAt[s.end - 1] = new IndexItem<>(a, endsAt[s.end - 1]);
  }

  /**
   * Returns a new index that includes the given action without modifying the
   * current index.
   */
  public SpanIndex<T> persistentUpdate(T a) {
    IndexItem<T> all = new IndexItem<>(a, this.all);
//    if (!a.hasSpan()) {
//      // Nothing changes, can re-use this index
//      return new ActionIndex(all, this.coversToken, this.startsAt, this.endsAt);
//    }
    // NOTE: sparse update
    Span s = a.getSpan();
    int n = coversToken.length;
    IndexItem<T>[] nCrossesToken = Arrays.copyOf(coversToken, n);
    IndexItem<T>[] nStartsAt = Arrays.copyOf(startsAt, n);
    IndexItem<T>[] nEndsAt = Arrays.copyOf(endsAt, n);
    for (int i = s.start; i < s.end; i++)
      nCrossesToken[i] = new IndexItem<>(a, nCrossesToken[i]);
    nStartsAt[s.start] = new IndexItem<>(a, nStartsAt[s.start]);
    nEndsAt[s.end - 1] = new IndexItem<>(a, nEndsAt[s.end - 1]);
    return new SpanIndex<>(all, nCrossesToken, nStartsAt, nEndsAt);
  }
}