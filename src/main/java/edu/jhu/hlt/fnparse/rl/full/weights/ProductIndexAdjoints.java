package edu.jhu.hlt.fnparse.rl.full.weights;

import java.util.List;

import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.tutils.scoring.Adjoints;

/**
 * Like Adjoints.Vector, but takes a List<ProductIndex> and converts it to
 * an sparse binary feature representation (int[]).
 *
 * TODO Move to tutils.
 *
 * @author travis
 */
public class ProductIndexAdjoints implements Adjoints {
  private int[] featIdx;
  private LazyL2UpdateVector weights;
  private double l2Reg;
  private double lr;

  public ProductIndexAdjoints(double learningRate, double l2Reg, int dimension, List<ProductIndex> features, LazyL2UpdateVector weights) {
    this.lr = learningRate;
    this.l2Reg = l2Reg;
    this.weights = weights;
    this.featIdx = new int[features.size()];
    for (int i = 0; i < featIdx.length; i++)
      featIdx[i] = features.get(i).getProdFeatureModulo(dimension);
  }

  @Override
  public double forwards() {
    double d = 0;
    for (int i = 0; i < featIdx.length; i++)
      d += weights.weights.get(featIdx[i]);
    return d;
  }

  @Override
  public void backwards(double dErr_dForwards) {
    double a = lr * -dErr_dForwards;
    for (int i = 0; i < featIdx.length; i++)
      weights.weights.add(featIdx[i], a);
    weights.maybeApplyL2Reg(l2Reg);
  }
}