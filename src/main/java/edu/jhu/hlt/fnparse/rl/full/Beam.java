package edu.jhu.hlt.fnparse.rl.full;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TreeSet;

import edu.jhu.hlt.tutils.scoring.Adjoints;

public interface Beam {

  public void offer(State next);

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

  public State pop();

  /**
   * Compares first on score, then on State.sig.
   * Will never return compareTo == 0,
   * thus you should only ever construct pairs of instances s.t. (score,sig) are unique!
   */
  public static class BeamItem implements Comparable<BeamItem> {
    public final State state;
    public final double score;
    public BeamItem(State state, double score) {
      this.state = state;
      this.score = score;
    }
    @Override
    public int compareTo(BeamItem o) {
      if (score < o.score)
        return +1;
      if (score > o.score)
        return -1;
      BigInteger s1 = state.getSig();
      BigInteger s2 = o.state.getSig();
      int c = s1.compareTo(s2);
      if (c == 0) {
        if (this != o) {  // TreeSet/Map calls compare(key,key) for some stupid reason...
          System.err.println("state=" + state.show());
          System.err.println("other=" + o.state.show());
          throw new RuntimeException("should not have duplicates!");
        }
      }
      return c;
    }
  }

  /**
   * Differs from tutils Beam in that it checks for State equality first (taking
   * the higher scoring of two items) before resorting to beam pruning.
   *
   * Note: Behavior for tied scores is undefined. I'm not sure that a deterministic
   * tie-breaker (e.g. first/last to be added) is a good idea, so you may want
   * to add a little jitter to the scores.
   */
  public static class DoubleBeam implements Beam {
    private TreeSet<BeamItem> scores;
    private HashMap<State, BeamItem> table;   // ensures that entries in scores are unique up to State
    private int capacity;
    private int numCollapses, numOffers;

    public DoubleBeam(int capacity) {
      this.capacity = capacity;
      this.table = new HashMap<>((int) (capacity * 1.5 + 1));
      this.scores = new TreeSet<>();
      this.numCollapses = 0;
      this.numOffers = 0;
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
      Iterator<BeamItem> itr = scores.iterator();
      while (itr.hasNext()) {
        BeamItem i = itr.next();
        sb.append(String.format("  %.3f %s\n", i.score, i.state));
      }
      sb.append(')');
      return sb.toString();
    }

    /**
     * Assumes that {@link Adjoints}s are cached and calls to forwards() are cheap.
     */
    @Override
    public void offer(State s) {
      numOffers++;
      double sc = s.score.forwards();
      BeamItem old = table.get(s);
      if (old != null) {
        numCollapses++;
        if (old.score < sc) {
          // If this state is the same as something on our beam,
          // then choose the higher scoring of the two.
          scores.remove(old);
          BeamItem si = new BeamItem(s, sc);
          boolean added = scores.add(si);
          assert added;
          table.put(s, si);
        }
        // else no op: we've proven this state is equivalent and lower-scoring than something we know about
      } else if (scores.size() < capacity) {
        // If this is a new state and we have room, then add this item without eviction
        BeamItem si = new BeamItem(s, sc);
        scores.add(si);
        table.put(s, si);
      } else if (sc > lowerBound()) {
        // Remove the worst item on the beam.
        BeamItem worst = scores.pollLast();
        table.remove(worst.state);
        // Add this item
        BeamItem si = new BeamItem(s, sc);
        scores.add(si);
        BeamItem old2 = table.put(s, si);
        assert old2 == null;
      }
    }

    @Override
    public Double lowerBound() {
      if (size() == 0)
        return null;
      return scores.last().score;
    }

    @Override
    public State pop() {
      BeamItem bi = scores.pollFirst();
      BeamItem r = table.remove(bi.state);
      assert r != null;
      return bi.state;
    }

    public int size() { return scores.size(); }
    public int capacity() { return capacity; }
  }
}