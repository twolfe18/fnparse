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
  // Use this for benchmarking: surround a block with gets/sets of these.
  public static int COUNTER_CONSTRUCT = 0;
  public static int COUNTER_FORWARDS = 0;
  public static int COUNTER_BACKWARDS = 0;
  public static void zeroCounters() {
    COUNTER_CONSTRUCT = 0;
    COUNTER_FORWARDS = 0;
    COUNTER_BACKWARDS = 0;
  }
  public static void logCounters() {
    Log.info("nConstruct=" + COUNTER_CONSTRUCT
        + " nForwards=" + COUNTER_FORWARDS
        + " nBacwards=" + COUNTER_BACKWARDS);
  }

  private int[] featIdx;
  private LazyL2UpdateVector weights;
  private double l2Reg;
  private double lr;
  boolean attemptL2Update;

  // For debugging
  public String nameOfWeights = null;
  public Alphabet<?> showUpdatesWith = null;
  public String[] showUpdatesWithAlt = null;
  public int forwardsCount = 0;

  public ProductIndexAdjoints(WeightsInfo weights, List<ProductIndex> features, boolean attemptApplyL2Update) {
    this(weights.learningRate, weights.l2Reg, weights.dimension(), features, weights.weights, attemptApplyL2Update);
  }

  public ProductIndexAdjoints(
      double learningRate, double l2Reg, int dimension,
      List<ProductIndex> features, LazyL2UpdateVector weights,
      boolean attemptApplyL2Update) {
    if (weights == null)
      throw new IllegalArgumentException();
    this.attemptL2Update = attemptApplyL2Update;
    this.lr = learningRate;
    this.l2Reg = l2Reg;
    this.weights = weights;
    this.featIdx = new int[features.size()];
    for (int i = 0; i < featIdx.length; i++)
      featIdx[i] = features.get(i).getProdFeatureModulo(dimension);
    COUNTER_CONSTRUCT++;
  }

  @Override
  public String toString() {
    return String.format(
        "(ProdIdxAdj forwards=%.2f numFeat=%d l2Reg=%.1g lr=%2f)",
        forwards(), featIdx.length, l2Reg, lr);
  }

  @Override
  public double forwards() {
    // Counter example: the static feature cache. I can't cache those because
    // the weights will change. Though I still want to cache the features/ProductIndexAdjoints

//    assert (++forwardsCount) <= 5 : "you probably should wrap this is a caching";
//    forwardsCount++;
//    if (forwardsCount > 1)
//      throw new RuntimeException();

    double d = 0;
    for (int i = 0; i < featIdx.length; i++)
      d += weights.weights.get(featIdx[i]);
    COUNTER_FORWARDS++;
    return d;
  }

  @Override
  public void backwards(double dErr_dForwards) {
    double a = lr * -dErr_dForwards;
    for (int i = 0; i < featIdx.length; i++)
      weights.weights.add(featIdx[i], a);

    if (nameOfWeights != null) {
      Log.info(String.format("dErr_dForwards=%.3f lr=%.3f weights=%s", dErr_dForwards, lr, System.identityHashCode(weights.weights)));
      for (int i = 0; i < featIdx.length; i++) {
        String fs;
        if (showUpdatesWith != null)
          fs = showUpdatesWith.lookupObject(featIdx[i]).toString();
        else if (showUpdatesWithAlt != null)
          fs = showUpdatesWithAlt[featIdx[i]];
        else
          fs = null;
        System.out.printf("w[%s,%d,%s] += %.2f\n", nameOfWeights, featIdx[i], fs, a);
      }
      System.out.println();
    }

    if (attemptL2Update) {
      Log.info("why?");
      assert false;
      weights.maybeApplyL2Reg(l2Reg);
    }
    COUNTER_BACKWARDS++;
  }
}