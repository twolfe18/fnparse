package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.util.HasSpan;
import edu.jhu.hlt.tutils.Span;

/**
 * SpanIndex doesn't care about (t,k), only (i,j). This class allows you to
 * give queries that specify (t,k). I'm not sure what the best way to do this
 * based on time/space, so this interface may have a few implementations.
 *
 * @author travis
 */
public interface ActionSpanIndex<T extends HasSpan> {

  public void mutableUpdate(T a);

  /** How many Actions are in this index, equivalently how many times mutableUpdate was called */
  public int size();

  public <C extends Collection<T>> C containedIn(int t, int k, Span container, C addTo);
  public <C extends Collection<T>> C containedIn(int t, Span container, C addTo);
  public <C extends Collection<T>> C containedIn(Span container, C addTo);

  public <C extends Collection<T>> C notContainedIn(int t, int k, Span container, C addTo);
  public <C extends Collection<T>> C notContainedIn(int t, Span container, C addTo);
  public <C extends Collection<T>> C notContainedIn(Span container, C addTo);

  public <C extends Collection<T>> C crosses(int t, int k, Span s, C addTo);
  public <C extends Collection<T>> C crosses(int t, Span s, C addTo);
  public <C extends Collection<T>> C crosses(Span s, C addTo);

  /**
   * Keeps track of size but doesn't index or store any of the actions.
   * All index lookup methods will return null.
   */
  public static class None<A extends Adjoints.HasSpan>
      implements ActionSpanIndex<A> {
    private int size = 0;
    @Override
    public void mutableUpdate(A a) {
      size++;
    }
    @Override
    public int size() {
      return size;
    }
    @Override
    public <C extends Collection<A>> C containedIn(int t, int k, Span container, C addTo) {
      return null;
    }
    @Override
    public <C extends Collection<A>> C containedIn(int t, Span container, C addTo) {
      return null;
    }
    @Override
    public <C extends Collection<A>> C containedIn(Span container, C addTo) {
      return null;
    }
    @Override
    public <C extends Collection<A>> C notContainedIn(int t, int k, Span container, C addTo) {
      return null;
    }
    @Override
    public <C extends Collection<A>> C notContainedIn(int t, Span container, C addTo) {
      return null;
    }
    @Override
    public <C extends Collection<A>> C notContainedIn(Span container, C addTo) {
      return null;
    }
    @Override
    public <C extends Collection<A>> C crosses(int t, int k, Span s, C addTo) {
      return null;
    }
    @Override
    public <C extends Collection<A>> C crosses(int t, Span s, C addTo) {
      return null;
    }
    @Override
    public <C extends Collection<A>> C crosses(Span s, C addTo) {
      return null;
    }
  }

  /**
   * Keeps one ActionIndex and does (t,k) filtering at query time.
   */
  public static class SpaceEfficient<A extends Adjoints.HasSpan>
      implements ActionSpanIndex<A> {
    private SpanIndex<A> all;
    private int size;
    public SpaceEfficient(int sentenceLength) {
      all = new SpanIndex<>(sentenceLength);
      size = 0;
    }
    @Override
    public void mutableUpdate(A a) {
      all.mutableUpdate(a);
      size++;
    }
    @Override
    public int size() {
      return size;
    }

    @Override
    public <C extends Collection<A>> C containedIn(int t, int k, Span container, C addTo) {
      assert implies(t < 0, k < 0);
      if (t < 0 && k < 0) {
        // No filtering
        return all.containedIn(container, addTo);
      }
      List<A> temp = all.containedIn(container, new ArrayList<>());
      for (A a : temp) {
        if ((t < 0 || t == a.getT())
            && (k < 0 || k == a.getK())) {
          addTo.add(a);
        }
      }
      return addTo;
    }
    @Override
    public <C extends Collection<A>> C containedIn(int t, Span container, C addTo) {
      return containedIn(t, -1, container, addTo);
    }
    @Override
    public <C extends Collection<A>> C containedIn(Span container, C addTo) {
      return containedIn(-1, -1, container, addTo);
    }

    @Override
    public <C extends Collection<A>> C notContainedIn(int t, int k, Span container, C addTo) {
      assert implies(t < 0, k < 0);
      if (t < 0 && k < 0) {
        // No filtering
        return all.notContainedIn(container, addTo);
      }
      List<A> temp = all.notContainedIn(container, new ArrayList<>());
      for (A a : temp) {
        if ((t < 0 || t == a.getT())
            && (k < 0 || k == a.getK())) {
          addTo.add(a);
        }
      }
      return addTo;
    }
    @Override
    public <C extends Collection<A>> C notContainedIn(int t, Span container, C addTo) {
      return notContainedIn(t, -1, container, addTo);
    }
    @Override
    public <C extends Collection<A>> C notContainedIn(Span container, C addTo) {
      return notContainedIn(-1, -1, container, addTo);
    }

    @Override
    public <C extends Collection<A>> C crosses(int t, int k, Span s, C addTo) {
      assert implies(t < 0, k < 0);
      if (t < 0 && k < 0) {
        // No filtering
        return all.crosses(s, addTo);
      }
      List<A> temp = all.crosses(s, new ArrayList<>());
      for (A a : temp) {
        if ((t < 0 || t == a.getT())
            && (k < 0 || k == a.getK())) {
          addTo.add(a);
        }
      }
      return addTo;
    }
    @Override
    public <C extends Collection<A>> C crosses(int t, Span s, C addTo) {
      return crosses(t, -1, s, addTo);
    }
    @Override
    public <C extends Collection<A>> C crosses(Span s, C addTo) {
      return crosses(-1, -1, s, addTo);
    }
  }

  public static boolean implies(boolean a, boolean b) {
    return !a || (a && b);
  }

  /**
   * Keeps an ActionIndex for every (t,k) and t, where queries should be just
   * as fast as ActionIndex.
   */
  public abstract static class TimeEfficient<A extends HasSpan>
      implements ActionSpanIndex<A> {
    // TODO
  }
}
