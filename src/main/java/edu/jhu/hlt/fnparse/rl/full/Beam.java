package edu.jhu.hlt.fnparse.rl.full;

import java.util.PriorityQueue;
import java.util.TreeSet;

public interface Beam {

  public void offer(State next, Adjoints score);

  /**
   * Score of worst item that is first in line to be pruned, or null if size is
   * not (yet) constrained.
   */
  public Double lowerBound();

  public static class PqBeam implements Beam {
    private PriorityQueue<State> pq;
    private int capacity;

    @Override
    public void offer(State s, Adjoints score) {
      PriorityQueue<Double> pq = new PriorityQueue<>();
      pq.offer(score.forwards());

      TreeSet<Double> ts = new TreeSet<>();
      ts.first();
      ts.ad
    }

    @Override
    public Double lowerBound() {
      // TODO Auto-generated method stub
      return null;
    }
  }
}