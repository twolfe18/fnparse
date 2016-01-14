package edu.jhu.hlt.fnparse.rl.full2;

import java.io.Serializable;
import java.util.List;

import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.vector.IntDoubleDenseVector;

/**
 * See http://www.ciml.info/dl/v0_8/ciml-v0_8-ch03.pdf
 *
 * @author travis
 */
public class AveragedPerceptronWeights implements Serializable, ProductIndexWeights {
  private static final long serialVersionUID = 708063405325278777L;

  private IntDoubleDenseVector w;
  private IntDoubleDenseVector u;
  private int c;
  private int dimension;

  public AveragedPerceptronWeights(int dimension) {
    this.w = new IntDoubleDenseVector(dimension);
    this.u = new IntDoubleDenseVector(dimension);
    this.c = 0;
    this.dimension = dimension;
  }

  public void zeroWeights() {
    w = new IntDoubleDenseVector(dimension);
  }
  public void zeroWeightsAverage() {
    u = new IntDoubleDenseVector(dimension);
    c = 0;
  }

  public String summary() {
    return "(APW L2=" + w.getL2Norm() + " dim=" + dimension + " c=" + c + ")";
  }

  @Override
  public int dimension() {
    return dimension;
  }

  @Override
  public Adjoints score(List<ProductIndex> features) {
    return this.new Adj(features);
  }

  /** Aliased to getWeight */
  public double get(int i) {
    return getWeight(i);
  }
  public double getWeight(int i) {
    assert i >= 0 && i < dimension;
    return w.get(i);
  }
  public double getAveragedWeight(int i) {
    assert i >= 0 && i < dimension;
    if (c == 0) {
      assert w.get(i) == 0;
      return 0;
    }
    return w.get(i) - (1d/c) * u.get(i);
  }

  /** Returns a new instance with the weights set to the average */
  public AveragedPerceptronWeights computeAverageWeights() {
    assert c > 0;
    AveragedPerceptronWeights a = new AveragedPerceptronWeights(dimension);
    for (int i = 0; i < dimension; i++) {
      double wi = getAveragedWeight(i);
      a.w.set(i, wi);
      a.u.set(i, wi);
    }
    a.c = 1;
    return a;
  }

  public void completedObservation() {
    c++;
  }
  public int numObervations() {
    return c;
  }

  /**
   * Reads from weights (not averaged weights). Backwards performs update to
   * weights and average, but you must still call
   * {@link AveragedPerceptronWeights#completedObservation()} separately.
   */
  public class Adj implements Adjoints {
    private int[] features;

    public Adj(List<ProductIndex> features) {
      this.features = new int[features.size()];
      for (int i = 0; i < this.features.length; i++)
        this.features[i] = features.get(i).getProdFeatureModulo(dimension);
    }

    @Override
    public double forwards() {
      double d = 0;
      for (int i = 0; i < features.length; i++)
        d += getWeight(features[i]);
      return d;
    }

    @Override
    public void backwards(double dErr_dForwards) {
      for (int i = 0; i < features.length; i++) {
        w.add(features[i], -dErr_dForwards);
        u.add(features[i], c * -dErr_dForwards);
      }
    }
  }
}
