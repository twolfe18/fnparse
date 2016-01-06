package edu.jhu.hlt.fnparse.rl.full;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeSet;

import edu.jhu.hlt.fnparse.rl.full2.LLSSP;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;

public interface Beam<T extends StateLike> {

  /** Returns true if the item was added to the beam (not pruned) */
  public boolean offer(T next);

  /*
   * tutils' Beam uses TreeSet.add which uses Beam.Item.compareTo
   * The problem here is that it does not handle item equality.
   * It uses scoreTieBreaker:int when the score is the same, and scoreTieBreaker is never the same for any two Items.
   *
   * The java.util.PriorityQueue has the same problem: it doesn't handle equality.
   * The desired behaviour is if two states are the same, take the one with a better score. 
   * Both methods will either:
   * a) assess them to be the same and then always take the first, regardless of score or
   * b) assess them to be different and keep the one with the worse score.
   *
   * We need a Set<State> for dedup and PriorityQueue<State> for top-k
   */

  /**
   * Score of worst item that is first in line to be pruned, or null if size is
   * not (yet) constrained.
   */
  public Double lowerBound();

  public T pop();


  /**
   * Compares first on score, then on State.sig.
   * Will never return compareTo == 0,
   * thus you should only ever construct pairs of instances s.t. (score,sig) are unique!
   */
  public static class BeamItem<R extends StateLike> implements Comparable<BeamItem<R>> {
    public static long INSTANCE_COUNTER = 0;

    public final R state;
    public final double score;
    public final long inst;

    public BeamItem(R state, double score) {
      this.state = state;
      this.score = score;
      this.inst = INSTANCE_COUNTER++;
    }
    @Override
    public int compareTo(BeamItem<R> o) {
      if (score < o.score)
        return -1;
      if (score > o.score)
        return +1;
      if (this == o)
        return 0;
      if (LLSSP.DISABLE_PRIMES) {
//        return 0;
        assert inst != o.inst || this == o;
        assert inst >= 0 && o.inst >= 0;
        return inst < o.inst ? +1 : -1;
      }
      BigInteger s1 = state.getSignature();
      BigInteger s2 = o.state.getSignature();
      int c = s1.compareTo(s2);
      if (c == 0) {
        if (this != o) {  // TreeSet/Map calls compare(key,key) for some stupid reason...
          System.err.println("state=" + state);
          System.err.println("other=" + o.state);
          throw new RuntimeException("should not have duplicates!");
        }
      }
      return c;
    }
    @Override
    public boolean equals(Object other) {
      if (this == other)
        return true;
      Log.warn("wat");
      return false;
    }
  }

  /** See methods in {@link MaxLoss} for what these mean */
  public enum Mode {
    MIN_LOSS,
    H_LOSS,
    MAX_LOSS,
    MAX_LOSS_LIN,
    MAX_LOSS_POW,
  }

  /**
   * Differs from tutils Beam in that it checks for State equality first (taking
   * the higher scoring of two items) before resorting to beam pruning.
   *
   * Note: Behavior for tied scores is undefined. I'm not sure that a deterministic
   * tie-breaker (e.g. first/last to be added) is a good idea, so you may want
   * to add a little jitter to the scores.
   */
  public static class DoubleBeam<T extends StateLike> implements Beam<T> {
    // This is sort of like a bijection because:
    // scores: BeamItem -> State + ...
    // table: State -> BeamItem
    private TreeSet<BeamItem<T>> scores;
    private HashMap<T, BeamItem<T>> table;   // ensures that entries in scores are unique up to State
    private int capacity;
    private int numCollapses, numOffers;
    private SearchCoefficients coefs;

    public DoubleBeam(HowToSearch hts) {
      this(hts.beamSize(), hts);
    }

    public DoubleBeam(int capacity, SearchCoefficients coefs) {
      this.capacity = capacity;
      if (LLSSP.DISABLE_PRIMES) {
        // All states are assumed to be unique, don't attempt to call equals
        // which will at best waste time calling getSig and at worst falsely
        // assume two States are the same because of some dummy primeProd value.
        this.table = null;
      } else {
        this.table = new HashMap<>((int) (capacity * 1.5 + 1));
      }
      this.scores = new TreeSet<>();
      this.numCollapses = 0;
      this.numOffers = 0;
      this.coefs = coefs;
    }

    public SearchCoefficients getCoefficients() {
      return coefs;
    }

    public void clear() {
      scores.clear();
      if (table != null)
        table.clear();
      numCollapses = 0;
      numOffers = 0;
    }

    public int getNumOffers() {
      return numOffers;
    }

    public double getCollapseRate() {
      return ((double) numCollapses) / numOffers;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(String.format("(Beam %d/%d collapseRate=%.3f\n", size(), capacity, getCollapseRate()));
      Iterator<BeamItem<T>> itr = scores.iterator();
      while (itr.hasNext()) {
        BeamItem<T> i = itr.next();
        sb.append(String.format("  %.3f %s\n", i.score, i.state));
      }
      sb.append(')');
      return sb.toString();
    }

    /**
     * Assumes that {@link Adjoints}s are cached and calls to forwards() are cheap.
     */
    @Override
    public boolean offer(T s) {
      numOffers++;
      double sc = coefs.forwards(s.getStepScores());

      assert !Double.isNaN(sc);
      if (Double.isInfinite(sc)) {
        // Don't use +inf to force an action. Use -inf to prevent one from
        // happening and then ensure that there is at least one possible thing.
        // You can use finite +const to reward good behavior.
        assert sc < 0;
        return false;
      }

      BeamItem<T> old = table == null ? null : table.get(s);
      if (old != null) {
        numCollapses++;
        if (old.score < sc) {
          // If this state is the same as something on our beam,
          // then choose the higher scoring of the two.
          scores.remove(old);
          BeamItem<T> si = new BeamItem<>(s, sc);
          boolean added = scores.add(si);
          assert added;
          table.put(s, si);
          return true;
        }
        // else no op: we've proven this state is equivalent and lower-scoring than something we know about
        return false;
      } else if (scores.size() < capacity) {
        // If this is a new state and we have room, then add this item without eviction
        BeamItem<T> si = new BeamItem<>(s, sc);
        int j = scores.size();
        scores.add(si);
        int k = scores.size();
        assert k == j+1 : "before=" + j + " after=" + k + " item=" + si;
        if (table != null)
          table.put(s, si);
        return true;
      } else if (sc > lowerBound()) {
        // Remove the worst item on the beam.
        BeamItem<T> worst = scores.pollFirst();
        if (table != null)
          table.remove(worst.state);
        // Add this item
        BeamItem<T> si = new BeamItem<>(s, sc);
        scores.add(si);
        if (table != null) {
          BeamItem<T> old2 = table.put(s, si);
          assert old2 == null;
        }
        return true;
      } else {
        return false;
      }
    }

    @Override
    public Double lowerBound() {
      if (size() == 0)
        return null;
      assert scores.first().score <= scores.last().score;
      return scores.first().score;
    }

    public T peek() {
      if (scores.isEmpty())
        throw new IllegalStateException();
      return scores.last().state;
    }

    @Override
    public T pop() {
      BeamItem<T> bi = scores.pollLast();
      if (table != null) {
        BeamItem<T> r = table.remove(bi.state);
        assert r != null;
      }
      return bi.state;
    }

    public Iterator<BeamItem<T>> iterator() {
      return scores.descendingIterator();
    }

    public int size() { return scores.size(); }
    public int capacity() { return capacity; }
  }
}