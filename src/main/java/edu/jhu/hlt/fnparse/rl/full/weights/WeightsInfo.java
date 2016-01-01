package edu.jhu.hlt.fnparse.rl.full.weights;

import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;

public class WeightsInfo {

  LazyL2UpdateVector weights;
  private int dimension;
  public double learningRate = 0.1;
  public double l2Reg = 1e-6;

  public WeightsInfo(LazyL2UpdateVector weights, int dimension, double learningRate, double l2Reg) {
    this.weights = weights;
    this.dimension = dimension;
    this.learningRate = learningRate;
    this.l2Reg = l2Reg;
  }

  /** Forwards to {@link LazyL2UpdateVector#maybeApplyL2Reg(double)} */
  public void maybeApplyL2Reg() {
    weights.maybeApplyL2Reg(l2Reg);;
  }

  public void scale(double scale) {
    weights.weights.scale(scale);
  }

  public double getL2Norm() {
    return weights.weights.getL2Norm();
  }

  public double get(int i) {
    return weights.weights.get(i);
  }

  public int dimension() {
    return dimension;
  }

  @Override
  public String toString() {
    return summary();
  }

  public String summary() {
    return String.format("(L2=%.3f D=%d L2Reg=%.2g lr=%.2g)",
        getL2Norm(), dimension, l2Reg, learningRate);
  }
}