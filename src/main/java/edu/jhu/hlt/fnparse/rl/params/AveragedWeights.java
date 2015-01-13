package edu.jhu.hlt.fnparse.rl.params;

import edu.jhu.gm.feat.FeatureVector;

// How can I lift this averaging out of this class?
public class AveragedWeights {
  private double[] theta;
  private double[] thetaSum;
  private int c;

  public AveragedWeights(int dimension, boolean average) {
    c = 1;
    theta = new double[dimension];
    if (average)
      thetaSum = new double[dimension];
  }

  public int dimension() {
    assert thetaSum == null || thetaSum.length == theta.length;
    return theta.length;
  }

  public void grow(int dimension) {
    throw new RuntimeException("implement me");
  }

  public void add(double[] a) {
    throw new RuntimeException("implement me");
  }

  public void add(FeatureVector a) {
    throw new RuntimeException("implement me");
  }

  public void add(int index, double value) {
    throw new RuntimeException("implement me");
  }

  public void incrementCount() {
    c++;
  }

  public double[] getWeights() {
    return theta;
  }

  public double[] getAveragedWeights() {
    throw new RuntimeException("implement me");
  }
}