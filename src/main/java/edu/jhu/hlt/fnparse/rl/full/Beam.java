package edu.jhu.hlt.fnparse.rl.full;

public interface Beam {
  public void offer(State next, Adjoints score);
  public Double lowerBound(); // score of worst item that is first in line to be pruned, or null if size is not (yet) constrained
}