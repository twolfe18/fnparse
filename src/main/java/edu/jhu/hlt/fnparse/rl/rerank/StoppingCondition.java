package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import edu.jhu.hlt.fnparse.util.EMA;
import edu.jhu.hlt.fnparse.util.QueueAverage;
import edu.jhu.hlt.tutils.InputStreamGobbler;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Timer;

public interface StoppingCondition {

  public boolean stop(int iter, double violation);
  public int estimatedNumberOfIterations();

  /**
   * The point of this class is to track dev set loss across many iterations and
   * save the model every time. When a wrapped/provided {@link StoppingCondition}
   * returns true, this will re-instantiate the best params. IO is abstracted
   * over using a user-provided function for saving and loading models file a
   * file.
   */
  public static class ArgMinDevLossParamSetter implements StoppingCondition {
    private StoppingCondition wrapped;
    private File paramDir;
    private PriorityQueue<Model> models;
    private Consumer<File> modelSerializeWeights;
    private Consumer<File> modelDeserializeWeights;

    public ArgMinDevLossParamSetter(
        StoppingCondition wrapping,
        File paramDir,
        Consumer<File> saveWeightsToFile,
        Consumer<File> restoreWeightsFromFile) {
      if (!paramDir.isDirectory())
        throw new IllegalArgumentException(paramDir.getPath() + " is not a directory");
      Log.info("[main] wrapping=" + wrapping + " paramDir=" + paramDir.getPath());
      this.wrapped = wrapping;
      this.paramDir = paramDir;
      this.models = new PriorityQueue<>();
      this.modelSerializeWeights = saveWeightsToFile;
      this.modelDeserializeWeights = restoreWeightsFromFile;
    }

    @Override
    public boolean stop(int iter, double violation) {

      File mf = new File(paramDir, "model-" + System.currentTimeMillis() + ".bin");
      Log.info("[main] writing iter=" + iter + " violation=" + violation + " to=" + mf.getPath());
      modelSerializeWeights.accept(mf);
      models.offer(new Model(mf, violation));

      boolean st = wrapped.stop(iter, violation);
      if (st) {
        // Find the best parameters and re-assign them
        Model best = models.peek();
        Log.info("[main] restoring params from " + best);
        modelDeserializeWeights.accept(best.file);
      }

      return st;
    }

    @Override
    public int estimatedNumberOfIterations() {
      return wrapped.estimatedNumberOfIterations();
    }

    /** A model, storing its performance and location on disk */
    private static class Model implements Comparable<Model> {
      public final File file;
      public final double loss;
      public Model(File file, double loss) {
        this.file = file;
        this.loss = loss;
      }
      @Override
      public int compareTo(Model o) {
        if (loss < o.loss)
          return -1;
        if (loss > o.loss)
          return +1;
        return 0;
      }
      @Override
      public String toString() {
        return "(Model file=" + file.getPath() + " loss=" + loss + ")";
      }
    }
  }

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
        Log.info(toString() + " stopping because of " + left);
        return true;
      }
      if (right.stop(iter, violation)) {
        Log.info(toString() + " stopping because of " + right);
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
    private long start;
    private int iterEstimate = -1;
    private int iter;
    private double maxMinutes;
    public Time(double maxMinutes) {
      this.maxMinutes = maxMinutes;
      this.start = System.currentTimeMillis();
    }
    @Override
    public String toString() {
      return String.format("Time(%.1f minutes)", maxMinutes);
    }
    @Override
    public boolean stop(int iter, double violation) {
      this.iter = iter;
      if (iter % INTERVAL != 0)
        return false;
      boolean stop = elapsedMins() > maxMinutes;
      if (stop)
        Log.info(toString() + " stopping due to time");
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
    public double getMaxMinutes() {
      return maxMinutes;
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
   * Computes a t-statistic on the differences in two values:
   * 1) the (mean,var) of the previous k evaluations of the dev set loss
   * 2) the (mean,var) of the next predicted value under a locally-weighted
   *    linear regression.
   * If the t-statistic is greater than the given value (alpha), then stop.
   */
  public static class DevSet implements StoppingCondition {

    private File rScript;
    private File historyFile;
    private FileWriter historyFileWriter;
    private int historySize;
    private int skipFirst = 1;

    private DoubleSupplier devLossFunc;
    private final double alpha;
    private final double k;
    private Timer rScriptTimer;

    /**
     * @param rScript is a path to a 3-arg shell script which prints either
     * "stop" or "continue" to standard out.
     * @param devLossFunc computes the loss on the dev set
     * @param alpha should be between 0 and 1, where small values will stop
     * quickly and large values will stop late.
     * @param k is the width of the kernel (larger values indicate more history
     * is taken into consideration). Good default is 25.
     * @param skipFirst is how many calls to devLossFunc should be skipped, as
     * the first value returned is typically no-where near convergence, and can
     * mess up the variance estimates.
     */
    public DevSet(File rScript, DoubleSupplier devLossFunc, double alpha, double k, int skipFirst) {
      if (!rScript.isFile())
        throw new IllegalArgumentException(rScript.getPath() + " is not a file");
      if (skipFirst < 0)
        throw new IllegalArgumentException("skipFirst=" + skipFirst);
      this.rScript = rScript;
      this.skipFirst = skipFirst;
      try {
        this.historySize = 0;
        this.historyFile = File.createTempFile("devSetLoss", ".txt");
        this.historyFileWriter = new FileWriter(historyFile);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      this.alpha = alpha;
      this.k = k;
      this.devLossFunc = devLossFunc;
      this.rScriptTimer = new Timer()
        .setPrintInterval(1)
        .ignoreFirstTime(false);
    }

    @Override
    public String toString() {
      return String.format("DevSet(%s,alpha=%.2f,k=%.2f,s=%d)",
          historyFile.getPath(), alpha, k, skipFirst);
    }

    /**
     * This class keeps an open FileWriter for writing out this history, and
     * this method closes that writer.
     */
    public void close() {
      try {
        historyFileWriter.close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public boolean stop(int iter, double violation) {

      // Compute held-out loss
      Log.info("[DevSet stop] calling dev set loss function");
      double devLoss = devLossFunc.getAsDouble();
      Log.info("[DevSet stop] writing loss=" + devLoss + " to file=" + historyFile.getPath());
      assert Double.isFinite(devLoss);
      assert !Double.isNaN(devLoss);
      assert devLoss >= 0 : "technically this isn't needed...";
      this.historySize++;
      if (historySize > skipFirst) {
        try {
          this.historyFileWriter.write(devLoss + "\n");
          this.historyFileWriter.flush();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }

      if (historySize - skipFirst < (int) (0.5 * k) + 1)
        return false;

      // Call rScript
      Log.info("[DevSet stop] iter=" + iter + " calling " + toString());
      rScriptTimer.start();
      try {
        ProcessBuilder pb = new ProcessBuilder(
            rScript.getPath(), historyFile.getPath(), "" + alpha, "" + k);
        Process p = pb.start();
        InputStreamGobbler stdout = new InputStreamGobbler(p.getInputStream());
        InputStreamGobbler stderr = new InputStreamGobbler(p.getErrorStream());
        stdout.start();
        stderr.start();
        int r = p.waitFor();
        if (r != 0 || stdout.getLines().isEmpty()) {
          Log.warn("stderr: " + stderr.getLines());
          Log.warn(p);
          Log.warn("[DevSet stop] error during call: " + r);
          //throw new RuntimeException("exit value: " + r);
          return false;
        }
        String guidance = stdout.getLines().get(0).trim();
        if ("stop".equalsIgnoreCase(guidance)) {
          return true;
        }
        assert "continue".equalsIgnoreCase(guidance) : "guidance: " + guidance;
      } catch (Exception e) {
        throw new RuntimeException(e);
      } finally {
        double secs = rScriptTimer.stop() / 1000d;
        if (secs > 1d)
          Log.warn("[DevSet stop] slow rScript, secs=" + secs);
      }
      return false;
    }

    @Override
    public int estimatedNumberOfIterations() {
      return this.historySize * 2;
    }
  }

  /**
   * Keeps two buckets of violations of size K and stops when the absolute or
   * relative reduction in the averages of the two buckets drops below a
   * threshold. Will require at least 2*K iterations.
   * 
   * NOTE: I think this is a little more predictable and easy to use than EMAConvergence.
   */
  public static class AvgErrReduction implements StoppingCondition {
    private QueueAverage<Double> olderBucket, newBucket;
    private int bucketSize;
    private double minAbsRedPerIter;
    private double minRelRedPerIter;
    private double maxPvalue;
    private int iter;
    private int decorrelate;  // only check every decorrelate iters

    /**
     * Will stop when a two sample t-test of two buckets yields a p-value
     * greater than maxPvalue.
     */
    public AvgErrReduction(int bucketSize, double maxPvalue) {
      this.bucketSize = bucketSize;
      this.olderBucket = new QueueAverage<>(bucketSize);
      this.newBucket = new QueueAverage<>(bucketSize);
      this.iter = 0;
      this.maxPvalue = maxPvalue;
      this.minAbsRedPerIter = Double.NEGATIVE_INFINITY;
      this.minRelRedPerIter = Double.NEGATIVE_INFINITY;
      // bucketSize >= 100, check every iteration
      // bucketSize =   50, check every 2 iterations
      // bucketSize =   10, check every 4 iterations
      this.decorrelate = Math.max(1, (int) Math.ceil(10d / Math.sqrt(bucketSize)));
    }

    /**
     * @deprecated use the p-value version instead
     */
    public AvgErrReduction(int bucketSize, double minAbsReductionPerIter, double minRelReductionPerIter) {
      this.bucketSize = bucketSize;
      this.olderBucket = new QueueAverage<>(bucketSize);
      this.newBucket = new QueueAverage<>(bucketSize);
      this.minAbsRedPerIter = minAbsReductionPerIter;
      this.minRelRedPerIter = minRelReductionPerIter;
      this.maxPvalue = Double.POSITIVE_INFINITY;
      this.iter = 0;
      // bucketSize >= 100, check every iteration
      // bucketSize =   50, check every 2 iterations
      // bucketSize =   10, check every 4 iterations
      this.decorrelate = Math.max(1, (int) Math.ceil(10d / Math.sqrt(bucketSize)));
    }

    public void reset() {
      olderBucket.clear();
      newBucket.clear();
    }

    @Override
    public String toString() {
      boolean af = Double.isFinite(minAbsRedPerIter);
      boolean rf = Double.isFinite(minRelRedPerIter);
      boolean pf = Double.isFinite(maxPvalue);
      assert af == rf;
      assert pf != (af && rf);
      if (pf) {
        return String.format("AvgErrReduction(k=%d,p=%.2f)",
            bucketSize, maxPvalue);
      } else {
        return String.format("AvgErrReduction(k=%d,abs=%.2g,rel=%.2g)",
            bucketSize, minAbsRedPerIter, minRelRedPerIter);
      }
    }

    @Override
    public boolean stop(int iter, double violation) {
      // Add this violation to our estimate
      this.iter++;
      if (!olderBucket.isFull()) {
        Log.info(toString() + " pushing to old");
        olderBucket.push(violation);
        return false;
      } else if (!newBucket.isFull()) {
        Log.info(toString() + " pushing to new");
        newBucket.push(violation);
        return false;
      }
      // Cascade from new to old
      double old = newBucket.push(violation);
      olderBucket.push(old);

      // Estimate the error reduction per iteration
      double hi = olderBucket.getAverage();
      double lo = newBucket.getAverage();
      double absRedPerIter = (hi - lo) / bucketSize;
      double relRedPerIter = ((hi - lo) / hi) / bucketSize;

      // Do t-test on bucket means
      double oldMeanVar = olderBucket.getVariance() / bucketSize;
      double newMeanVar = newBucket.getVariance() / bucketSize;
      double t = absRedPerIter / Math.sqrt(oldMeanVar + newMeanVar);

      Log.info(String.format(
          "%s iter=%d reduction=%.2g absRedPerIter=%.2g relRedPerIter=%.2g "
          + "iter%%decorrelate=%d t=%.3f oldMeanVar=%.3f newMeanVar=%.3f",
          toString(), this.iter, hi - lo, absRedPerIter, relRedPerIter,
          iter % decorrelate, t, oldMeanVar, newMeanVar));

      if (this.iter % decorrelate == 0) {
        boolean a = absRedPerIter < this.minAbsRedPerIter;
        boolean b = relRedPerIter < this.minRelRedPerIter;
        boolean p = t > this.maxPvalue;
        if (p)
          Log.info(toString() + " stopping because of t-test");
        if (a && b)
          Log.info(toString() + " stopping because of absolute and relative error");
        else if (a)
          Log.info(toString() + " stopping because of absolute error");
        else if (b)
          Log.info(toString() + " stopping because of relative error");
        return p || a || b;
      } else {
        return false;
      }
    }

    @Override
    public int estimatedNumberOfIterations() {
      return (int) (this.iter * 1.5 + 10);
    }
  }

  /**
   * Keeps two exponentially weighted moving averages (EMA), one fast, one slow.
   * When they converge (stay within a given tolerance for a certain number of
   * iterations), stops the learning.
   */
  public static class EMAConvergence implements StoppingCondition {
    public static boolean VERBOSE = false;
    private final EMA slow, fast;
    private final double tol;
    private final int inARow;
    private int curRun;
    private int iterations;
    public EMAConvergence() {
      this(5.0, 5);
    }
    public EMAConvergence(double tolerance, int inARow) {
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
        Log.info("[HammingConvergence] iter=" + iter + " tol=" + tol
            + " slow=" + slow.getAverage() + " fast=" + fast.getAverage()
            + " violation=" + violation + " red=" + red);
      }
      if (Math.abs(red) < tol && fast.getNumUpdates() > 1) {
        curRun++;
        if (curRun == inARow)
          return true;
        int k = Math.max(1, inARow / 10);
        if (curRun % k == 0) {
          Log.info("[HammingConvergence] curRun=" + curRun
              + " inARow=" + inARow + " iter=" + iter);
        }
      } else {
        double t = Math.min(0.25d, inARow / (iter + 1));
        if (((double) curRun) / inARow > t) {
          Log.info("[HammingConvergence] resetting after curRun=" + curRun
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

  public static void main(String[] args) {
    File rScript = new File("scripts/stop.sh");
    double alpha = 0.15;
    double k = 25;
    DoubleSupplier devLossFunc = new DoubleSupplier() {
      private Random rand = new Random(9001);
      private int iter = 0;
      @Override
      public double getAsDouble() {
        double y = 1000d - 0.5d * iter + 1.5d * Math.pow(75d - iter, 2d);
        double n = rand.nextGaussian() * 1000d;
        System.out.println("eval y=" + y + " n=" + n + " sum=" + (y+n));
        iter++;
        return y + n;
      }
    };
    StoppingCondition.DevSet stop = new StoppingCondition.DevSet(rScript, devLossFunc, alpha, k, 0);
    for (int i = 0; i < 200; i++) {
      boolean s = stop.stop(i, 0);
      System.out.println("stop=" + s);
      if (s) break;
    }
  }
}