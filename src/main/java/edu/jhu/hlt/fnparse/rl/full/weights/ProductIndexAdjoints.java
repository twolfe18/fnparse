package edu.jhu.hlt.fnparse.rl.full.weights;

import java.util.List;

import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.util.Alphabet;

/**
 * Like Adjoints.Vector, but takes a List<ProductIndex> and converts it to
 * an sparse binary feature representation (int[]).
 *
 * NOTE: The {@link ProductIndex}s coming into the constructor do NOT need to
 * have already been taking modulo dimension, this does that for you.
 * Dimension should match the length of weights though.
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

  // For debugging
  public String nameOfWeights = null;
  public Alphabet<?> showUpdatesWith = null;
  public int forwardsCount = 0;

  public ProductIndexAdjoints(WeightsInfo weights, List<ProductIndex> features) {
    this(weights.learningRate, weights.l2Reg, weights.dimension(), features, weights.weights);
  }

  public ProductIndexAdjoints(double learningRate, double l2Reg, int dimension, List<ProductIndex> features, LazyL2UpdateVector weights) {
    if (weights == null)
      throw new IllegalArgumentException();
    this.lr = learningRate;
    this.l2Reg = l2Reg;
    this.weights = weights;
    this.featIdx = new int[features.size()];
    for (int i = 0; i < featIdx.length; i++)
      featIdx[i] = features.get(i).getProdFeatureModulo(dimension);
  }

  @Override
  public String toString() {
    return String.format(
        "(ProdIdxAdj forwards=%.2f numFeat=%d l2Reg=%.1g lr=%2f)",
        forwards(), featIdx.length, l2Reg, lr);
  }

  @Override
  public double forwards() {
//    assert (++forwardsCount) < 4 : "you probably should wrap this is a caching";
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

    if (showUpdatesWith != null) {
      Log.info(String.format("dErr_dForwards=%.3f lr=%.3f weights=%s", dErr_dForwards, lr, System.identityHashCode(weights.weights)));
      for (int i = 0; i < featIdx.length; i++) {
        String fs = showUpdatesWith.lookupObject(featIdx[i]).toString();
        System.out.printf("w[%s,%d,%s] += %.2f\n", nameOfWeights, featIdx[i], fs, a);
      }
      System.out.println();
    }

    weights.maybeApplyL2Reg(l2Reg);
  }
}