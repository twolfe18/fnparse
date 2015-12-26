package edu.jhu.hlt.fnparse.rl.full;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeSet;

import edu.jhu.hlt.tutils.scoring.Adjoints;

public interface Beam<T extends StateLike> {

  public void offer(T next);

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
    public final R state;
    public final double score;
    public BeamItem(R state, double score) {
      this.state = state;
      this.score = score;
    }
    @Override
    public int compareTo(BeamItem<R> o) {
      if (score < o.score)
        return +1;
      if (score > o.score)
        return -1;
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
//    private Mode mode;

    // TODO Replace Mode with:
    private SearchCoefficients coefs;

    public DoubleBeam(HowToSearch hts) {
      this(hts.beamSize(), hts);
    }

//    public DoubleBeam(int capacity, Mode mode) {
    public DoubleBeam(int capacity, SearchCoefficients coefs) {
      this.capacity = capacity;
      this.table = new HashMap<>((int) (capacity * 1.5 + 1));
      this.scores = new TreeSet<>();
      this.numCollapses = 0;
      this.numOffers = 0;
//      this.mode = mode;
      this.coefs = coefs;
    }

    public void clear() {
      scores.clear();
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

//    public double value(T s) {
//      switch (mode) {
//      case MIN_LOSS:
//        return s.getStepScores().forwardsMin();
//      case H_LOSS:
//        return s.getStepScores().forwardsH();
//      case MAX_LOSS:
//        return s.getStepScores().forwardsMax();
//      case MAX_LOSS_LIN:
//        return s.getStepScores().forwardsMaxLin();
//      case MAX_LOSS_POW:
//        return s.getStepScores().forwardsMaxPow();
//      default:
//        throw new RuntimeException("unknown mode: " + mode);
//      }
//    }

    /**
     * Assumes that {@link Adjoints}s are cached and calls to forwards() are cheap.
     */
    @Override
    public void offer(T s) {
      numOffers++;
//      double sc = value(s);
      double sc = coefs.forwards(s.getStepScores());
      BeamItem<T> old = table.get(s);
      if (old != null) {
        
        
//        State2<?> s1 = (State2<?>) s;
//        State2<?> s2 = (State2<?>) old.state;
//
//        System.out.println("\ns1");
//        s1.getRoot().show(System.out);
//        System.out.println("\nold");
//        s2.getRoot().show(System.out);
//
//        System.out.println("s1 sig: " + s1.getRoot().getSig());
//        System.out.println("s2 sig: " + s2.getRoot().getSig());
//
//        if (s1 != s2)
//          throw new RuntimeException();
        
        
        numCollapses++;
        if (old.score < sc) {
          // If this state is the same as something on our beam,
          // then choose the higher scoring of the two.
          scores.remove(old);
          BeamItem<T> si = new BeamItem<>(s, sc);
          boolean added = scores.add(si);
          assert added;
          table.put(s, si);
        }
        // else no op: we've proven this state is equivalent and lower-scoring than something we know about
      } else if (scores.size() < capacity) {
        // If this is a new state and we have room, then add this item without eviction
        BeamItem<T> si = new BeamItem<>(s, sc);
        scores.add(si);
        table.put(s, si);
      } else if (sc > lowerBound()) {
        // Remove the worst item on the beam.
        BeamItem<T> worst = scores.pollLast();
        table.remove(worst.state);
        // Add this item
        BeamItem<T> si = new BeamItem<>(s, sc);
        scores.add(si);
        BeamItem<T> old2 = table.put(s, si);
        assert old2 == null;
      }
    }

    @Override
    public Double lowerBound() {
      if (size() == 0)
        return null;
      return scores.last().score;
    }

    public T peek() {
      if (scores.isEmpty())
        throw new IllegalStateException();
      return scores.first().state;
    }

    @Override
    public T pop() {
      BeamItem<T> bi = scores.pollFirst();
      BeamItem<T> r = table.remove(bi.state);
      assert r != null;
      return bi.state;
    }

    public int size() { return scores.size(); }
    public int capacity() { return capacity; }
  }
}