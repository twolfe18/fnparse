package edu.jhu.hlt.fnparse.rl;

/**
 * parameterizes a score function on (state,action) pairs
 */
public interface Params {
  /**
   * Q-function, score of a action from a state
   */
  public Adjoints score(State s, Action a);

  /**
   * updates the parameters for taking a in s and receiving a reward
   */
  public void update(Adjoints a, double reward);
}