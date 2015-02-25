package edu.jhu.hlt.fnparse.util;

import java.util.Arrays;

import org.apache.log4j.Logger;

public class LearningRateEstimator {
  public static final Logger LOG = Logger.getLogger(LearningRateEstimator.class);
  public static boolean VERBOSE = true;

  /**
   * What this class needs out of a model to perform its function.
   */
  public static interface Model {
    public double getLearningRate();
    public void setLearningRate(double learningRate);
    public void train();
    public double loss(); // Must be >=0 and lower is better
    public void saveParams(String identifier);
    public void loadParams(String identifier);
  }

  /**
   * When this function exits, the learning rate will be set appropriately and
   * the model parameters will be set to what was found after the best rollout.
   *
   * @param m
   * @param queries must be an integer which can be expressed as 2*k+1. This
   *        function will attempt k values that are lower than the current
   *        learning rate, k that are higher, and use the same one to compare.
   * @param spread is the base of the learning rates being explored,
   *        i.e. [spread^-k, spread^(-k+1), ... spread^k]
   */
  public static void estimateLearningRate(Model m, int queries, double spread) {
    if (queries % 2 == 0 || queries < 3)
      throw new IllegalArgumentException("queries=" + queries);
    long start = System.currentTimeMillis();

    // Compute the learning rates
    double[] lr = new double[queries];
    double lr0 = m.getLearningRate();
    for (int i = 0; i < queries; i++)
      lr[i] = lr0 * Math.pow(spread, i - queries / 2);
    LOG.info("[estimateLearningRate] lr0=" + lr0 + " options=" + Arrays.toString(lr));

    // Save the initial configuration (need to reset to this before each rollout)
    LOG.info("[estimateLearningRate] saving starting point of all rollouts");
    m.saveParams("init");

    // Perform rollouts and store the results
    String[] ids = new String[queries];
    double[] loss = new double[queries];
    int bestConf = 0;
    double bestLoss = Double.POSITIVE_INFINITY;
    for (int i = 0; i < lr.length; i++) {
      if (i > 0) m.loadParams("init");
      m.setLearningRate(lr[i]);
      LOG.info("[estimateLearningRate] training for lr=" + lr[i]);
      m.train();
      LOG.info("[estimateLearningRate] testing for lr=" + lr[i]);
      loss[i] = m.loss();
      if (Double.isInfinite(loss[i]) || Double.isNaN(loss[i])) {
        loss[i] = 999.9;
      }
      ids[i] = "after-lr-" + i;
      LOG.info("[estimateLearningRate] lr=" + lr[i] + " loss=" + loss[i]);
      if (loss[i] < bestLoss) {
        bestLoss = loss[i];
        bestConf = i;
        LOG.info("[estimateLearningRate] this is best, saving params");
        m.saveParams(ids[i]);
      }
    }

    // Set the state to the best config
    LOG.info("[estimateLearningRate] loading configuration from lr=" + lr[bestConf]);
    m.loadParams(ids[bestConf]);

    // Choose the learning rate that is a exp(regret) + lambda*lrMult weighted average
    // The bigger lambda is, the more we trust just regret in loss.
    double lrFinal;
    if (bestConf == 0 || bestConf == lr.length-1) {
      LOG.info("[estimateLearningRate] chose extreme value, not smoothing");
      lrFinal = lr[bestConf];
    } else {
      double lambda = 0.5 * Math.pow(variance(loss), -0.25);
      double gamma = 0.1;
      double lrSum = 0, lrZ = 0;
      for (int i = 0; i < queries; i++) {
        double r = gamma * (loss[i] - bestLoss) + lambda * i;
        double w = Math.exp(-r);
        lrSum += lr[i] * w;
        lrZ += w;
        LOG.info("[estimateLearningRate] smooth lr=" + lr[i] + " r=" + r + " w=" + w);
      }
      lrFinal = lrSum / lrZ;
    }
    LOG.info("[estimateLearningRate] done, took "
        + (System.currentTimeMillis() - start)/1000d + " seconds, lr=" + lrFinal);
    assert Double.isFinite(lrFinal) && !Double.isNaN(lrFinal);
    m.setLearningRate(lrFinal);
  }

  public static double variance(double[] values) {
    assert values.length > 1;
    double sx = 0, sxx = 0;
    for (double v : values) {
      sx += v;
      sxx += v * v;
    }
    double mean = sx / values.length;
    double variance = (sxx - (values.length - 1)) - (mean * mean);
    return variance;
  }
}
