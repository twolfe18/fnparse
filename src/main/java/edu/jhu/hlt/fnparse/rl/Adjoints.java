package edu.jhu.hlt.fnparse.rl;

/**
 * wraps the result of a forward pass, which is often needed for computing a
 * gradient.
 */
public interface Adjoints {
  public double getScore();
  public Action getAction();
}
