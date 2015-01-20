package edu.jhu.hlt.fnparse.rl.rerank;

import edu.jhu.hlt.fnparse.util.EMA;


public interface StoppingCondition {

  public boolean stop(int iter, double violation);
  public int estimatedNumberOfIterations();

  /** A fixed number of iterations */
  public static class Fixed implements StoppingCondition {
    private int maxIter;
    public Fixed(int maxIter) {
      this.maxIter = maxIter;
    }
    public String toString() {
      return "MaxIter(" + maxIter + ")";
    }
    public int getMaxIterations() {
      return maxIter;
    }
    public boolean stop(int iter, double violation) {
      return iter >= maxIter;
    }
    @Override
    public int estimatedNumberOfIterations() {
      return maxIter;
    }
  }

  /**
   * Keeps two exponentially weighted moving averages, one fast and one slow,
   * and when they converge (stay within a given tolerance for a certain number
   * of iterations), stops the learning.
   */
  public static class HammingConvergence implements StoppingCondition {
    private final EMA slow, fast;
    private final double tol;
    private final int inARow;
    private int curRun;
    private int iterations;
    public HammingConvergence() {
      this(5.0, 5);
    }
    public HammingConvergence(double tolerance, int inARow) {
      slow = new EMA(0.9);
      fast = new EMA(0.1);
      tol = tolerance;
      this.inARow = inARow;
      curRun = 0;
      iterations = 0;
    }
    public String toString() {
      return String.format("HammingConvergence(%.2f,%.2f,%.2f,%d)",
          slow.getHistory(), fast.getHistory(), tol, inARow);
    }
    @Override
    public boolean stop(int iter, double violation) {
      iterations++;
      slow.update(violation);
      fast.update(violation);
      double red = slow.getAverage() - fast.getAverage();
      RerankerTrainer.LOG.info("[HammingConvergence] iter=" + iter + " tol=" + tol
          + " slow=" + slow.getAverage() + " fast=" + fast.getAverage()
          + " violation=" + violation + " red=" + red);
      if (Math.abs(red) < tol && red > 0d && fast.getNumUpdates() > 1) {
        curRun++;
        if (curRun == inARow)
          return true;
      } else {
        curRun = 0;
      }
      return false;
    }
    @Override
    public int estimatedNumberOfIterations() {
      // Total guess...
      double r0 = fast.getAverage() / slow.getAverage();
      if (r0 < 1) r0 = 1d / r0;
      double r1 = ((double) (iterations + 100)) / iterations;
      double r = r0/2 + r1/2;
      return (int) (r * iterations + 0.5d);
    }
  }
}