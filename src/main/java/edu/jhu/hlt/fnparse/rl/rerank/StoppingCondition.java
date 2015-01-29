package edu.jhu.hlt.fnparse.rl.rerank;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.util.EMA;


public interface StoppingCondition {
  public static final Logger LOG = Logger.getLogger(StoppingCondition.class);

  public boolean stop(int iter, double violation);
  public int estimatedNumberOfIterations();

  public static class Conjunction implements StoppingCondition {
    private StoppingCondition left, right;
    public Conjunction(StoppingCondition a, StoppingCondition b) {
      left = a;
      right = b;
    }
    @Override
    public String toString() {
      return "Conjunction(" + left + ", " + right + ")";
    }
    @Override
    public boolean stop(int iter, double violation) {
      if (left.stop(iter, violation)) {
        LOG.info(toString() + " stopping because of " + left);
        return true;
      }
      if (right.stop(iter, violation)) {
        LOG.info(toString() + " stopping because of " + right);
        return true;
      }
      return false;
    }
    @Override
    public int estimatedNumberOfIterations() {
      int l = left.estimatedNumberOfIterations();
      int r = right.estimatedNumberOfIterations();
      double har = 2d / (1d / l + 1d / r);
      double alpha = 0.8;
      return (int) (alpha * Math.min(l, r) + (1d - alpha) * har + 0.5d);
    }
  }

  public static class Time implements StoppingCondition {
    public static int INTERVAL = 1;
    private long start = -1;
    private int iterEstimate = -1;
    private int iter;
    private double maxMinutes;
    public Time(double maxMinutes) {
      this.maxMinutes = maxMinutes;
    }
    @Override
    public String toString() {
      return String.format("Time(%.1f minutes)", maxMinutes);
    }
    @Override
    public boolean stop(int iter, double violation) {
      if (start < 0)
        start = System.currentTimeMillis();
      this.iter = iter;
      if (iter % INTERVAL != 0)
        return false;
      boolean stop = elapsedMins() > maxMinutes;
      if (stop)
        LOG.info(toString() + " stopping due to time");
      return stop;
    }
    public double elapsedMins() {
      long end = System.currentTimeMillis();
      return (end - start) / (1000d * 60d);
    }
    @Override
    public int estimatedNumberOfIterations() {
      if (iterEstimate < 0 || iter % INTERVAL == 0) {
        double alpha = 0.75;  // how much to trust most recent estimate
        double newEst = iter * (maxMinutes / elapsedMins());
        iterEstimate = (int) (alpha * newEst + (1d - alpha) * iterEstimate + 0.5d);
      }
      return iterEstimate;
    }
  }

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
    public static boolean VERBOSE = false;
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
      assert !Double.isNaN(red);
      assert Double.isFinite(red);
      if (VERBOSE) {
        LOG.info("[HammingConvergence] iter=" + iter + " tol=" + tol
            + " slow=" + slow.getAverage() + " fast=" + fast.getAverage()
            + " violation=" + violation + " red=" + red);
      }
      if (Math.abs(red) < tol && fast.getNumUpdates() > 1) {
        curRun++;
        if (curRun == inARow)
          return true;
        int k = Math.max(1, inARow / 10);
        if (curRun % k == 0) {
          LOG.info("[HammingConvergence] curRun=" + curRun
              + " inARow=" + inARow + " iter=" + iter);
        }
      } else {
        double t = Math.min(0.25d, inARow / (iter + 1));
        if (((double) curRun) / inARow > t) {
          LOG.info("[HammingConvergence] resetting after curRun=" + curRun
              + " inARow=" + inARow + " iter=" + iter);
        }
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

  public static class NoViolations implements StoppingCondition {
    private int needed;
    private int curStreak;
    private int iterations;
    public NoViolations(int needed) {
      this.needed = needed;
      this.curStreak = 0;
      this.iterations = 0;
    }
    @Override
    public String toString() {
      return "NoViolations(" + needed + ")";
    }
    @Override
    public boolean stop(int iter, double violation) {
      iterations++;
      if (violation == 0) {
        curStreak++;
        if (curStreak >= needed)
          return true;
      } else {
        curStreak = 0;
      }
      return false;
    }
    @Override
    public int estimatedNumberOfIterations() {
      return (iterations + 1) * 2;
    }
  }
}