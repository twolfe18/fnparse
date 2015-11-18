package edu.jhu.hlt.fnparse.rl.full;

import java.util.HashMap;
import java.util.TreeMap;

import edu.jhu.hlt.tutils.scoring.Adjoints;

public interface Beam {

  public void offer(State next, Adjoints score);

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
   *
   * TODO Implement hashCode and equals for State
   */

  /**
   * Score of worst item that is first in line to be pruned, or null if size is
   * not (yet) constrained.
   */
  public Double lowerBound();

  /**
   * Differs from tutils Beam in that it checks for State equality first (taking
   * the higher scoring of two items) before resorting to beam pruning.
   *
   * Note: Behavior for tied scores is undefined. I'm not sure that a deterministic
   * tie-breaker (e.g. first/last to be added) is a good idea, so you may want
   * to add a little jitter to the scores.
   */
  public static class DoubleBeam implements Beam {
    private TreeMap<Double, State> scores;
    private HashMap<State, Adjoints> table;
    private int capacity;

    public DoubleBeam(int capacity) {
      this.capacity = capacity;
      this.table = new HashMap<>((int) (capacity * 1.5 + 1));
      this.scores = new TreeMap<>();
    }

    public void clear() {
      scores.clear();
      table.clear();
    }

    /**
     * Assumes that {@link Adjoints}s are cached and calls to forwards() are cheap.
     */
    @Override
    public void offer(State s, Adjoints score) {
      Adjoints old = table.get(s);
      if (old != null && old.forwards() < score.forwards()) {
        scores.remove(old.forwards());
        scores.put(score.forwards(), s);
        table.put(s, score);
      } else if (scores.size() < capacity) {
        scores.put(score.forwards(), s);
        table.put(s, score);
      } else {
        State evicted = scores.pollLastEntry().getValue();
        table.remove(evicted);
        scores.put(score.forwards(), s);
        table.put(s, score);
      }
    }

    @Override
    public Double lowerBound() {
      if (size() == 0)
        return null;
      return scores.lastKey();
    }

    public int size() { return scores.size(); }
    public int capacity() { return capacity; }
  }
}