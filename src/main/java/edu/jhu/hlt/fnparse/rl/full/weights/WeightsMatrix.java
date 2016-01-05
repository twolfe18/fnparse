package edu.jhu.hlt.fnparse.rl.full.weights;

import java.util.List;

import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.vector.IntDoubleDenseVector;

/**
 * A weights matrix where rows are indexed by T, and you provide a bijection
 * between T and int. Useful for doing a cheap product with T (you pass in
 * features which have different weights for each T, which is equivalent to
 * taking the product of those features with T).
 *
 * @author travis
 */
public abstract class WeightsMatrix<T> {
  private LazyL2UpdateVector[] at2w;
  private int dimension;
  private double l2Lambda;
  private double learningRate;

  public WeightsMatrix() {
    this(1 << 22, 32, 1e-6, 0.05);
  }

  public WeightsMatrix(int dimension, int updateInterval, double l2Lambda, double learningRate) {
    this.l2Lambda = l2Lambda;
    this.learningRate = learningRate;
    this.dimension = dimension;
    int N = numRows();
    this.at2w = new LazyL2UpdateVector[N];
    for (int i = 0; i < N; i++)
      this.at2w[i] = new LazyL2UpdateVector(new IntDoubleDenseVector(dimension), updateInterval);
  }

  public abstract int numRows();

  public abstract int row(T t);

  public Adjoints getScore(T t, List<ProductIndex> features) {
    if (features.isEmpty()) {
      return new Adjoints.OnlyShowScore("NoFeatures[" + t + "]", Adjoints.Constant.ZERO);
    }
    final LazyL2UpdateVector w = at2w[row(t)];
    return new ProductIndexAdjoints(learningRate, l2Lambda, dimension, features, w, true);
  }
}