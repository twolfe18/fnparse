package edu.jhu.hlt.fnparse.rl;

import java.util.Arrays;
import java.util.Collection;

import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * A persistent data structure for storing Actions by various properties.
 */
public class ActionIndex {

  // Linked list of Actions
  public static class IndexItem {
    public final Action action;               // payload
    public final ActionIndex.IndexItem prevNonEmptyItem;  // next node
    public final int size;                    // how many items in this linked list, including this payload
    public IndexItem(Action a, ActionIndex.IndexItem prev) {
      assert a != null;
      this.action = a;
      this.prevNonEmptyItem = prev;
      if (prev != null)
        this.size = prev.size + 1;
      else
        this.size = 1;
    }
    @Override
    public String toString() {
      return action + " -> " + prevNonEmptyItem;
    }
  }

  // These are all indexed by word
  private IndexItem[] coversToken;
  private IndexItem[] startsAt;
  private IndexItem[] endsAt;
  private IndexItem all;

  public ActionIndex(int sentenceLength) {
    this.all = null;
    this.coversToken = new ActionIndex.IndexItem[sentenceLength];
    this.startsAt = new ActionIndex.IndexItem[sentenceLength];
    this.endsAt = new ActionIndex.IndexItem[sentenceLength];
  }

  private ActionIndex(IndexItem all, ActionIndex.IndexItem[] coversToken, ActionIndex.IndexItem[] startsAt, ActionIndex.IndexItem[] endsAt) {
    this.all = all;
    this.coversToken = coversToken;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
  }

  public int size() {
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
  public <T extends Collection<Action>> T crosses(Span s, T addTo) {
    // Take all actions that appear in exactly one of:
    // actions that cross the first token and actions that cross the last token.
    // Below, implemented as all of the actions that cross the first token but
    // not the last.
    
    // (covers start XOR covers end) AND doesn't share an endpoint
    
    // This does not double count: the first loop will only take a span if it
    // doesn't cover end (and thus must start), and the second loop will only
    // take a span if it doesn't covers start (and thus must end).
    for (ActionIndex.IndexItem si = coversToken[s.start]; si != null; si = si.prevNonEmptyItem) {
      Action a = si.action;
      boolean coversEnd = a.start <= s.end-1 && s.end-1 < a.end;
      if (coversEnd) continue;
      boolean shareEndpoint = s.start == a.start || s.end == a.end;
      if (shareEndpoint) continue;
      addTo.add(a);
    }
    for (ActionIndex.IndexItem ei = coversToken[s.end - 1]; ei != null; ei = ei.prevNonEmptyItem) {
      Action a = ei.action;
      boolean coversStart = a.start <= s.start && s.start < a.end;
      if (coversStart) continue;
      boolean shareEndpoint = s.start == a.start || s.end == a.end;
      if (shareEndpoint) continue;
      addTo.add(a);
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
   * Returns a linked list of Actions such that their last token was i
   * (NOTE this is not that span.end == i, but rather span.end-1 == i because
   *  Spans are exclusive in end).
   * May return null if there are no such Actions.
   */
  public IndexItem endsAt(int i) {
    return endsAt[i];
  }

  /**
   * Returns a new index that includes the given action without modifying the
   * current index.
   */
  public ActionIndex updateIndex(Action a) {
    IndexItem all = new IndexItem(a, this.all);
    if (!a.hasSpan()) {
      // Nothing changes, can re-use this index
      return new ActionIndex(all, this.coversToken, this.startsAt, this.endsAt);
    }
    // NOTE: sparse update
    Span s = a.getSpan();
    int n = coversToken.length;
    ActionIndex.IndexItem[] nCrossesToken = Arrays.copyOf(coversToken, n);
    ActionIndex.IndexItem[] nStartsAt = Arrays.copyOf(startsAt, n);
    ActionIndex.IndexItem[] nEndsAt = Arrays.copyOf(endsAt, n);
    for (int i = s.start; i < s.end; i++)
      nCrossesToken[i] = new IndexItem(a, nCrossesToken[i]);
    nStartsAt[s.start] = new IndexItem(a, nStartsAt[s.start]);
    nEndsAt[s.end - 1] = new IndexItem(a, nEndsAt[s.end - 1]);
    return new ActionIndex(all, nCrossesToken, nStartsAt, nEndsAt);
  }
}