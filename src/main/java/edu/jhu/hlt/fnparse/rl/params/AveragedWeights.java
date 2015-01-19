package edu.jhu.hlt.fnparse.rl.params;

import java.util.Arrays;

import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;

/**
 * A dense vector of doubles that you can add to. Allows efficient averaging,
 * as used in the average perceptron. Call incrementCount() every time you add
 * a vector (which may not be every time add is called if you use the
 * add(int,double) version).
 *
 * Currently has no support for overflow detection, not sure how numerically
 * stable this is.
 *
 * @author travis
 */
public class AveragedWeights {
  public static final Logger LOG = Logger.getLogger(AveragedWeights.class);

  private double[] theta;
  private double[] thetaSum;
  private int c;    // how many iterates of theta have been added into thetaSum

  public AveragedWeights(int dimension) {
    this(dimension, true);
  }

  public AveragedWeights(int dimension, boolean average) {
    c = 1;
    theta = new double[dimension];
    if (average)
      thetaSum = new double[dimension];
  }

  public AveragedWeights(AveragedWeights weights, boolean average) {
    c = weights.c;
    theta = Arrays.copyOf(weights.theta, weights.theta.length);
    if (average) {
      assert weights.thetaSum != null;
      thetaSum = Arrays.copyOf(weights.thetaSum, weights.thetaSum.length);
    }
  }

  public boolean hasAverage() {
    return thetaSum != null;
  }

  public int dimension() {
    assert thetaSum == null || thetaSum.length == theta.length;
    return theta.length;
  }

  public void grow(int dimension) {
    if (dimension <= theta.length)
      throw new IllegalArgumentException();
    LOG.info("[grow] dimension " + theta.length + " => " + dimension);
    theta = Arrays.copyOf(theta, dimension);
    if (thetaSum != null)
      thetaSum = Arrays.copyOf(thetaSum, dimension);
  }

  public void add(double[] a) {
    if (a.length != theta.length)
      throw new IllegalArgumentException();
    for (int i = 0; i < a.length; i++)
      theta[i] += a[i];
    if (thetaSum != null) {
      for (int i = 0; i < a.length; i++)
        thetaSum[i] += c * a[i];
    }
  }

  public void add(FeatureVector a) {
    a.apply(new FnIntDoubleToDouble() {
      @Override
      public double call(int arg0, double arg1) {
        add(arg0, arg1);
        return arg1;
      }
    });
  }

  public void add(int index, double value) {
    theta[index] += value;
    if (thetaSum != null)
      thetaSum[index] += c * value;
  }

  public void incrementCount() {
    c++;
  }

  public double[] getWeights() {
    return theta;
  }

  public double[] getAveragedWeights() {
    if (thetaSum == null)
      throw new IllegalStateException("not in average mode");
    double[] avg = Arrays.copyOf(thetaSum, thetaSum.length);
    for (int i = 0; i < thetaSum.length; i++)
      avg[i] /= c;
    return avg;
  }

  public void setAveragedWeights() {
    theta = getAveragedWeights();
  }
}