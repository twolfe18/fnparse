package edu.jhu.hlt.fnparse.util;

import org.apache.commons.math3.util.FastMath;

import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer;

public interface LearningRateSchedule {

  public void observe(int iteration, double violation, int batchSize);

  public double learningRate();


  public static class Constant implements LearningRateSchedule {
    private final double learningRate;
    public Constant(double learningRate) {
      this.learningRate = learningRate;
    }
    @Override
    public void observe(int iteration, double violation, int batchSize) {
      // no-op
    }
    @Override
    public double learningRate() {
      return learningRate;
    }
  }

  public static class Normal implements LearningRateSchedule {
    private double initial;
    private double smooth;
    private double squish;
    private int iter;
    public Normal(double initial) {
      this(initial, 100d, 0.75d);
    }
    public Normal(double initial, double smooth, double squish) {
      if (squish > 1 || squish <= 0)
        throw new IllegalArgumentException();
      this.initial = initial;
      this.smooth = smooth;
      this.squish = squish;
      this.iter = 0;
    }
    @Override
    public void observe(int iteration, double violation, int batchSize) {
      this.iter = iteration;
    }
    @Override
    public double learningRate() {
      double it = FastMath.pow(iter + 1, squish);
      double lr = initial * smooth / (smooth + it);
      if (iter % 100 == 0)
        RerankerTrainer.LOG.info("[learningRate] iter=" + iter + " learningRate=" + lr);
      return lr;
    }
  }

  public static class Exp implements LearningRateSchedule {
    private final double decayRate;
    private int iter;
    public Exp(double decayRate) {
      this.decayRate = decayRate;
    }
    @Override
    public void observe(int iteration, double violation, int batchSize) {
      this.iter = iteration;
    }
    @Override
    public double learningRate() {
      double lr = FastMath.exp(-iter / decayRate);
      if (iter % 100 == 0)
        RerankerTrainer.LOG.info("[learningRate] iter=" + iter + " learningRate=" + lr);
      return lr;
    }
  }
}