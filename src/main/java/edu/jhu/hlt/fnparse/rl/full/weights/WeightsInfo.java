package edu.jhu.hlt.fnparse.rl.full.weights;

import java.util.List;

import edu.jhu.hlt.fnparse.rl.full2.LL;
import edu.jhu.hlt.fnparse.rl.full2.ProductIndexWeights;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.scoring.Adjoints;

public class WeightsInfo implements ProductIndexWeights {

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

  public WeightsInfo(WeightsInfo copyFrom) {
    weights = new LazyL2UpdateVector(copyFrom.weights);
    dimension = copyFrom.dimension;
    learningRate = copyFrom.learningRate;
    l2Reg = copyFrom.l2Reg;
  }

  public void add(WeightsInfo other) {
    assert dimension == other.dimension;
    weights.weights.add(other.weights.weights);
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

  @Override
  public int dimension() {
    return dimension;
  }

  @Override
  public Adjoints score(List<ProductIndex> features, boolean convertToArray) {
    if (!convertToArray)
      throw new RuntimeException("implement me");
    boolean attemptApplyL2Update = false;
    return new ProductIndexAdjoints(this, features, attemptApplyL2Update);
  }

  @Override
  public String toString() {
    return summary();
  }

  public String summary() {
    return String.format("(L2=%.3f D=%d L2Reg=%.2g lr=%.2g)",
        getL2Norm(), dimension, l2Reg, learningRate);
  }

  @Override
  public Adjoints score(LL<ProductIndex> features) {
    throw new RuntimeException("implement me");
  }
}