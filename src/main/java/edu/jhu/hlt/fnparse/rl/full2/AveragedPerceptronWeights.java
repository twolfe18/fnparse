package edu.jhu.hlt.fnparse.rl.full2;

import java.io.Serializable;
import java.util.List;

import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleVector;

/**
 * See http://www.ciml.info/dl/v0_8/ciml-v0_8-ch03.pdf
 *
 * @author travis
 */
public class AveragedPerceptronWeights implements Serializable, ProductIndexWeights {
  private static final long serialVersionUID = 708063405325278777L;

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

  // false=>649243 true=>546725
//  public static final boolean EAGER_FEATURE_MODULO = false;

  private IntDoubleDenseVector w;
  private IntDoubleDenseVector u;
  private double c;
  private int dimension;

  // Reserves the first K indices in w and u for special "intercept" features
  // which are not given out when score() is called. Calls to dimension() will
  // include these intercept indices.
  private int numInterceptFeatures;

  public AveragedPerceptronWeights(int dimension, int numIntercept) {
    this.w = new IntDoubleDenseVector(dimension);
    this.u = new IntDoubleDenseVector(dimension);
    this.c = 0;
    this.dimension = dimension;
    this.numInterceptFeatures = numIntercept;
  }

  public Adjoints intercept(int i) {
    assert i >= 0;
    if (i >= numInterceptFeatures) {
      throw new IllegalArgumentException("you didn't reserve enough intercepts: "
          + " i=" + i + " numIntercept=" + numInterceptFeatures);
    }
    return new Intercept(i);
  }

  public void zeroWeights() {
    w = new IntDoubleDenseVector(dimension);
  }
  public void zeroWeightsAverage() {
    u = new IntDoubleDenseVector(dimension);
    c = 0;
  }

  public void scale(double alpha, boolean includeAverage) {
    w.scale(alpha);
    if (includeAverage)
      u.scale(alpha);
  }

  /** Performs this += coef * w, does not affect c or u */
  public void addWeights(double coef, IntDoubleDenseVector w) {
    for (int i = 0; i < dimension; i++)
      this.w.add(i, coef * w.get(i));
  }

  public void addWeightsAndAverage(double coef, AveragedPerceptronWeights other) {
    for (int i = 0; i < dimension; i++)
      this.w.add(i, coef * other.w.get(i));
    for (int i = 0; i < dimension; i++)
      this.u.add(i, coef * other.u.get(i));
    c += coef * other.c;
  }

  // I don't need to do this if I average u and w from the train shards
  /** Does u += w, with a sign flip due to how Adj works, and zeros out w */
  public void addWeightsIntoAverage(boolean alsoZeroOutWeights) {
    c += 1;
    for (int i = 0; i < dimension; i++) {
      // u -= w because of how getAverageWeight works
      u.add(i, -w.get(i));
    }
    if (alsoZeroOutWeights)
      w.scale(0);
  }

  public void makeWeightsUnitLength() {
    makeUnitLength(w);
  }
  public void makeWeightsAverageUnitLength() {
    makeUnitLength(u);
    c = 1;
  }
  private static void makeUnitLength(IntDoubleVector u) {
    double l2 = u.getL2Norm();
    assert l2 > 1e-16 : "l2=" + l2;
    u.scale(1d / l2);
  }

  public String summary() {
    return "(APW L2=" + w.getL2Norm() + " dim=" + dimension + " c=" + c + ")";
  }

  @Override
  public int dimension() {
    return dimension;
  }

  @Override
  public Adjoints score(LL<ProductIndex> features) {
    return this.new Adj(features);
  }

  @Override
  public Adjoints score(List<ProductIndex> features, boolean convertToIntArray) {
    return this.new Adj(features, convertToIntArray);
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

  public IntDoubleDenseVector getInternalWeights() {
    return w;
  }

  /** Returns a new instance with the weights set to the average */
  public AveragedPerceptronWeights computeAverageWeights() {
    assert c > 0;
    AveragedPerceptronWeights a = new AveragedPerceptronWeights(dimension, numInterceptFeatures);
    for (int i = 0; i < dimension; i++) {
      double wi = getAveragedWeight(i);
      a.w.set(i, wi);
      a.u.set(i, wi);
    }
    a.c = 1;
    return a;
  }

  public void completedObservation() {
    c += 1;
  }
  public double numObervations() {
    return c;
  }

  public class Intercept implements Adjoints {
    private int index;
    public Intercept(int index) {
      this.index = index;
    }
    @Override
    public double forwards() {
      return getWeight(index);
    }
    @Override
    public void backwards(double dErr_dForwards) {
      double lr = 1;
      w.add(index, -lr * dErr_dForwards);
      u.add(index, c * -lr * dErr_dForwards);
    }
  }

  /**
   * Reads from weights (not averaged weights). Backwards performs update to
   * weights and average, but you must still call
   * {@link AveragedPerceptronWeights#completedObservation()} separately.
   */
  public class Adj implements Adjoints {
    // Contains correct feature indices (x -> numIntercept + x % (dimension-numIntercept))
    private int[] features;
    // Contains raw feature indices
    private List<ProductIndex> features2;
    private LL<ProductIndex> features3;

    public Adj(LL<ProductIndex> features) {
      features3 = features;
    }

    public Adj(List<ProductIndex> features, boolean convertToIntArray) {
      if (convertToIntArray) {
        this.features = new int[features.size()];
        for (int i = 0; i < this.features.length; i++)
          this.features[i] = reindex(features.get(i).getProdFeature());
      } else {
        this.features2 = features;
      }
      COUNTER_CONSTRUCT++;
    }

    private int reindex(long rawIndex) {
      assert rawIndex >= 0;
      long d = dimension - numInterceptFeatures;
      long x = ((long) numInterceptFeatures) + rawIndex % d;
      assert x <= ((long) dimension) && x >= 0;
      return (int) x;
    }

    @Override
    public double forwards() {
      double dot = 0;
      if (features3 != null) {
        for (LL<ProductIndex> pi = features3; pi != null; pi = pi.cdr())
          dot += getWeight(reindex(pi.car().getProdFeature()));
      } else if (features != null) {
        // No shift needed, done in constructor
        for (int i = 0; i < features.length; i++)
          dot += getWeight(features[i]);
      } else {
        for (ProductIndex pi : features2)
          dot += getWeight(reindex(pi.getProdFeature()));
      }
      COUNTER_FORWARDS++;
      return dot;
    }

    @Override
    public void backwards(double dErr_dForwards) {
      if (features3 != null) {
        for (LL<ProductIndex> pi = features3; pi != null; pi = pi.cdr()) {
          int i = reindex(pi.car().getProdFeature());
          w.add(i, -dErr_dForwards);
          u.add(i, c * -dErr_dForwards);
        }
      } else if (features != null) {
        for (int i = 0; i < features.length; i++) {
          w.add(features[i], -dErr_dForwards);
          u.add(features[i], c * -dErr_dForwards);
        }
      } else {
        for (ProductIndex pi : features2) {
          int i = reindex(pi.getProdFeature());
          w.add(i, -dErr_dForwards);
          u.add(i, c * -dErr_dForwards);
        }
      }
      COUNTER_BACKWARDS++;
    }
  }
}
