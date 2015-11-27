package edu.jhu.hlt.fnparse.rl;

import java.util.Arrays;
import java.util.Collection;

import edu.jhu.hlt.tutils.Span;

/**
 * A persistent data structure for storing things (e.g. Actions) that have a
 * Span by various Span-related properties.
 */
public class CommitIndex {

  // Linked list of Actions
  public static class IndexItem {
    public final Action payload;
    public final IndexItem prevNonEmptyItem;  // next node
    public final int size;                    // how many items in this linked list, including this payload
    public IndexItem(Action commit, IndexItem prev) {
      assert commit != null;
      this.payload = commit;
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
  private IndexItem[] coversToken;
  private IndexItem[] startsAt;
  private IndexItem[] endsAt;
  private IndexItem all;

  public CommitIndex(int sentenceLength) {
    this.all = null;
    this.coversToken = new IndexItem[sentenceLength];
    this.startsAt = new IndexItem[sentenceLength];
    this.endsAt = new IndexItem[sentenceLength + 1];
  }

  private CommitIndex(IndexItem all, IndexItem[] coversToken, IndexItem[] startsAt, IndexItem[] endsAt) {
    this.all = all;
    this.coversToken = coversToken;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
  }

  public int getSentenceSize() {
    assert coversToken.length == startsAt.length;
    assert startsAt.length == endsAt.length - 1;
    return coversToken.length;
  }

  public String toString() {
    int n = coversToken.length;
    StringBuilder sb = new StringBuilder("<CommitIndex " + n + "\n");
    for (int i = 0; i < n; i++)
      sb.append("covers[" + i + "] " + coversToken[i] + "\n");
    for (int i = 0; i < n; i++)
      sb.append("startsAt[" + i + "] " + startsAt[i] + "\n");
    for (int i = 1; i <= n; i++)
      sb.append("endsAt[" + i + "] " + endsAt[i] + "\n");
    sb.append(">");
    return sb.toString();
  }

  /**
   * Adds all actions that have a span that covers the given span to the
   * given collection of Actions.
   * @return the collection passed in.
   */
  public <C extends Collection<Action>> C crosses(Span s, C addTo) {
    return crosses(s.start, s.end, addTo);
  }
  public <C extends Collection<Action>> C crosses(int sstart, int send, C addTo) {
    // Take all actions that appear in exactly one of:
    // actions that cross the first token and actions that cross the last token.
    // Below, implemented as all of the actions that cross the first token but
    // not the last.
    
    // (covers start XOR covers end) AND doesn't share an endpoint
    
    // This does not double count: the first loop will only take a span if it
    // doesn't cover end (and thus must start), and the second loop will only
    // take a span if it doesn't covers start (and thus must end).
    for (IndexItem si = coversToken[sstart]; si != null; si = si.prevNonEmptyItem) {
      Action a = si.payload;
      if (!a.hasSpan()) continue;
      boolean coversEnd = a.start <= send-1 && send-1 < a.end;
      if (coversEnd) continue;
      boolean shareEndpoint = sstart == a.start || send == a.end;
      if (shareEndpoint) continue;
      addTo.add(a);
    }
    for (IndexItem ei = coversToken[send - 1]; ei != null; ei = ei.prevNonEmptyItem) {
      // JVM can't figure out that this is always Actions.HasSpan and inline calls
      // TODO try casting, or maybe putting in a type you know better?
      Action a = ei.payload;
      if (!a.hasSpan()) continue;
      boolean coversStart = a.start <= sstart && sstart < a.end;
      if (coversStart) continue;
      boolean shareEndpoint = sstart == a.start || send == a.end;
      if (shareEndpoint) continue;
      addTo.add(a);
    }
    return addTo;
  }

  /**
   * Finds all Actions that have a span inside of (token subset) container.
   */
  public <C extends Collection<Action>> C containedIn(Span container, C addTo) {
    if (container.end > startsAt.length) {
      throw new IllegalArgumentException("this container does not fit within "
          + "this index, using wrong index?");
    }
    for (int i = container.start; i < container.end; i++) {
      for (IndexItem ii = startsAt[i]; ii != null; ii = ii.prevNonEmptyItem) {
        Span s = ii.payload.getSpan();
        assert s.start == i;
        if (s.end <= container.end)
          addTo.add(ii.payload);
      }
    }
    return addTo;
  }

  /**
   * Finds all Actions that have a span which is not contained within the given
   * container (token subset).
   */
  public <C extends Collection<Action>> C notContainedIn(Span container, C addTo) {
    // Union of:
    // 1) s : s.start < container.start && s.end <= conainer.end
    for (int i = 0; i < container.start; i++) {
      for (IndexItem ii = startsAt[i]; ii != null; ii = ii.prevNonEmptyItem) {
        Span s = ii.payload.getSpan();
        if (s.end <= container.end)
          addTo.add(ii.payload);
      }
    }
    // 2) s : s.end > container.end && s.start >= container.start
    for (int j = container.end + 1; j < endsAt.length; j++) {
      for (IndexItem jj = endsAt[j]; jj != null; jj = jj.prevNonEmptyItem) {
        Span s = jj.payload.getSpan();
        if (s.start >= container.start)
          addTo.add(jj.payload);
      }
    }
    return addTo;
  }

  public IndexItem allActions() {
    return all;
  }

  /**
   * Returns a linked list of Actions such that their first token was i
   * (i.e. span.start == i).
   * May return null if there are no such Actions.
   */
  public IndexItem startsAt(int i) {
    return startsAt[i];
  }

  /**
   * Returns a linked list of Actions such that their end was i
   * (i.e. span.end == i)
   * May return null if there are no such Actions.
   */
  public IndexItem endsAt(int i) {
    return endsAt[i];
  }

  public void mutableUpdate(Action a) {
    all = new IndexItem(a, all);
    Span s = a.getSpan();
    for (int i = s.start; i < s.end; i++)
      coversToken[i] = new IndexItem(a, coversToken[i]);
    startsAt[s.start] = new IndexItem(a, startsAt[s.start]);
    endsAt[s.end] = new IndexItem(a, endsAt[s.end]);
  }

  /**
   * Returns a new index that includes the given action without modifying the
   * current index.
   */
  public CommitIndex persistentUpdate(Action a) {
    IndexItem all = new IndexItem(a, this.all);
//    if (!a.hasSpan()) {
//      // Nothing changes, can re-use this index
//      return new ActionIndex(all, this.coversToken, this.startsAt, this.endsAt);
//    }
    // NOTE: sparse update
    Span s = a.getSpan();
    IndexItem[] nCrossesToken = Arrays.copyOf(coversToken, coversToken.length);
    IndexItem[] nStartsAt = Arrays.copyOf(startsAt, startsAt.length);
    IndexItem[] nEndsAt = Arrays.copyOf(endsAt, endsAt.length);
    for (int i = s.start; i < s.end; i++)
      nCrossesToken[i] = new IndexItem(a, nCrossesToken[i]);
    nStartsAt[s.start] = new IndexItem(a, nStartsAt[s.start]);
    nEndsAt[s.end] = new IndexItem(a, nEndsAt[s.end]);
    return new CommitIndex(all, nCrossesToken, nStartsAt, nEndsAt);
  }
}
