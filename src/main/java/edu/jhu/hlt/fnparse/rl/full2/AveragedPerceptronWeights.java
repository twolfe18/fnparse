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

  /** Does u += w, with a sign flip due to how Adj works, and zeros out w */
  public void addWeightsIntoAverageAndZeroOutWeights(boolean alsoZeroOutWeights) {
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
    c += 1;
  }
  public double numObervations() {
    return c;
  }

  /**
   * Reads from weights (not averaged weights). Backwards performs update to
   * weights and average, but you must still call
   * {@link AveragedPerceptronWeights#completedObservation()} separately.
   */
  public class Adj implements Adjoints {
    private int[] features;
    private List<ProductIndex> features2;
    private LL<ProductIndex> features3;

    public Adj(LL<ProductIndex> features) {
      features3 = features;
    }

    public Adj(List<ProductIndex> features, boolean convertToIntArray) {
      if (convertToIntArray) {
        this.features = new int[features.size()];
        for (int i = 0; i < this.features.length; i++)
          this.features[i] = features.get(i).getProdFeatureModulo(dimension);
      } else {
        this.features2 = features;
      }
      COUNTER_CONSTRUCT++;
    }

    @Override
    public double forwards() {
      double d = 0;
      if (features3 != null) {
        for (LL<ProductIndex> pi = features3; pi != null; pi = pi.cdr())
          d += getWeight(pi.car().getProdFeatureModulo(dimension));
      } else if (features != null) {
        for (int i = 0; i < features.length; i++)
          d += getWeight(features[i]);
      } else {
        for (ProductIndex pi : features2)
          d += getWeight(pi.getProdFeatureModulo(dimension));
      }
      COUNTER_FORWARDS++;
      return d;
    }

    @Override
    public void backwards(double dErr_dForwards) {
      if (features3 != null) {
        for (LL<ProductIndex> pi = features3; pi != null; pi = pi.cdr()) {
          int i = pi.car().getProdFeatureModulo(dimension);
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
          int i = pi.getProdFeatureModulo(dimension);
          w.add(i, -dErr_dForwards);
          u.add(i, c * -dErr_dForwards);
        }
      }
      COUNTER_BACKWARDS++;
    }
  }
}
