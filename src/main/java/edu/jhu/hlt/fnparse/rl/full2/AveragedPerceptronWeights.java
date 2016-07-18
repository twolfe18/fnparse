package edu.jhu.hlt.fnparse.rl.full2;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleVector;
import edu.jhu.util.Alphabet;

/**
 * See http://www.ciml.info/dl/v0_8/ciml-v0_8-ch03.pdf
 *
 * TODO Add an option to use floats instead of doubles
 *
 * @author travis
 */
public class AveragedPerceptronWeights implements Serializable, ProductIndexWeights {
  private static final long serialVersionUID = 708063405325278777L;
  public static int DEBUG = 1;

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

  // TODO I'm such an idiot, I haven't added this to forwards()!
  // Nor have I specified how/when you specify which intercept features fire.
  // I believe originally I had been doing some monkey-hacking into the w/u fields to make this happen.
  //
  // Reserves the first K indices in w and u for special "intercept" features
  // which are not given out when score() is called. Calls to dimension() will
  // include these intercept indices.
  private int numInterceptFeatures;

  public boolean passiveAgressive;

  public AveragedPerceptronWeights(int dimension, int numIntercept) {
    this.w = new IntDoubleDenseVector(dimension);
    this.u = new IntDoubleDenseVector(dimension);
    this.c = 0;
    this.dimension = dimension;
    this.numInterceptFeatures = numIntercept;
    this.passiveAgressive =
        ExperimentProperties.getInstance().getBoolean("passiveAggressive", true);
    long b = (dimension * 2 * 8l) / (1l << 20);
    if (DEBUG > 0)
      Log.info("A dim=" + dimension + " nIntercept=" + numIntercept + " passiveAggressive=" + passiveAgressive + " size=" + b + " MB");
  }

  private AveragedPerceptronWeights(int dimension, int numIntercept, double c, boolean pa) {
    this.dimension = dimension;
    this.numInterceptFeatures = numIntercept;
    this.c = c;
    this.passiveAgressive = pa;
    if (DEBUG > 1) {
      long b = (dimension * 2 * 8l) / (1l << 20);
      Log.info("B dim=" + dimension + " nIntercept=" + numIntercept + " passiveAggressive=" + passiveAgressive + " size=" + b + " MB");
    }
  }

  public void gaussianFill(Random r) {
    int D = dimension();
    for (int i = 0; i < D; i++)
      w.set(i, r.nextGaussian());
    u.scale(0);
    c = 0;
  }

  /**
   * Only allows calls to getWeight, which is overridden to actually call
   * getAverageWeight. Allocates no space other than a pointer to the
   * {@link AveragedPerceptronWeights} which it is a view of.
   *
   * NOTE: If you are getting NPE with an average view and notice that the weight
   * vectors are null, this is intentional! Average views are never supposed to
   * be updated (have backwards called on them)!
   */
  public class AverageView extends AveragedPerceptronWeights {
    private static final long serialVersionUID = 5844615895964184994L;
    public AverageView(int dimension, int numIntercept, double c, boolean pa) {
      super(dimension, numIntercept, c, pa);
    }
    @Override
    public double getWeight(int i) {
      return AveragedPerceptronWeights.this.getAveragedWeight(i);
    }
    @Override
    public void completedObservation() {
      throw new RuntimeException("not allowed on a view");
    }
    @Override
    public double numObervations() {
      throw new RuntimeException("not allowed on a view");
    }
  }
  public AverageView averageView() {
    return new AverageView(dimension, numInterceptFeatures, c, passiveAgressive);
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
    for (int i = 0; i < dimension; i++) {
      // u -= w because of how getAverageWeight works
      u.add(i, -w.get(i));
    }
    if (alsoZeroOutWeights)
      w.scale(0);
    c += 1;
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
    double bytes = 2 * dimension * 8;
    String mem = String.format(" mem=%.1fGB", bytes / (1L<<30));
    return "(APW L2=" + w.getL2Norm() + " dim=" + dimension + " c=" + c + mem + ")";
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
  public Adj score(List<ProductIndex> features, boolean convertToIntArray) {
    return this.new Adj(features, convertToIntArray);
  }

  /** converts to int[] */
  public Adj score2(List<Integer> features, boolean reindex) {
    int[] a = new int[features.size()];
    for (int i = 0; i < a.length; i++)
      a[i] = features.get(i);
    return score(a, reindex);
  }

  public Adj score(int[] features, boolean reindex) {
    return this.new Adj(features, reindex);
  }

  public double getWeight(int i) {
    assert i >= 0 && i < dimension : "i=" + i + " dimension=" + dimension;
    return w.get(i);
  }
  public double getAveragedWeight(int i) {
    assert i >= 0 && i < dimension : "i=" + i + " dimension=" + dimension;
    if (c == 0) {
      assert w.get(i) == 0 : "are you calling completedObservation?";
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
      bwh(index, dErr_dForwards);
    }
  }

  // bwh = "backwards helper"
  private void bwh(int i, double dErr_dForwards) {
    assert !(this instanceof AverageView) : "don't call backwards on average views!";
    w.add(i, -dErr_dForwards);
    u.add(i, c * -dErr_dForwards);
  }

  public static String hexToString(int[] x, int maxCharsPerElem) {
    StringBuilder sb = new StringBuilder();
//    sb.append('[');
    for (int i = 0; i < x.length; i++) {
      if (i > 0) {
//        sb.append(", ");
        sb.append(',');
      }
      String s = Integer.toHexString(x[i]);
      if (s.length() > maxCharsPerElem) {
        // take low-order bits
        s = s.substring(s.length() - maxCharsPerElem, s.length());
      }
      s = s.toUpperCase();
      sb.append(s);
    }
//    sb.append(']');
    return sb.toString();
  }
  public static String hexToString(List<ProductIndex> x, int maxCharsPerElem) {
    int[] a = new int[x.size()];
    for (int i = 0; i < a.length; i++)
      a[i] = (int) x.get(i).getProdFeature();
    return hexToString(a, maxCharsPerElem);
  }
  public static String hexToString(LL<ProductIndex> x, int maxCharsPerElem) {
    List<ProductIndex> l = new ArrayList<>();
    for (LL<ProductIndex> cur = x; cur != null; cur = cur.next)
      l.add(cur.item);
    return hexToString(l, maxCharsPerElem);
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

    // For showing feature names if you know them
    public Alphabet<?> dbgAlphabet;

    int limit = 40;
    int maxCharsPerElem = 2;

    private String show(int[] features) {
      if (dbgAlphabet != null) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < features.length; i++) {
          if (i > 0)
            sb.append(", ");
          sb.append(dbgAlphabet.lookupObject(features[i]));
        }
        sb.append(']');
        return sb.toString();
      }
      return StringUtils.trunc(hexToString(features, maxCharsPerElem), limit);
    }
    public String show(List<ProductIndex> features) {
      // TODO
      return StringUtils.trunc(hexToString(features, maxCharsPerElem), limit);
    }
    public String show(LL<ProductIndex> features) {
      // TODO
      return StringUtils.trunc(hexToString(features, maxCharsPerElem), limit);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("(AvgPctW");
      if (features != null)
        sb.append("f=" + show(features));
      if (features2 != null)
        sb.append("f2=" + show(features2));
      if (features3 != null)
        sb.append("f3=" + show(features3));
      sb.append(')');
      return sb.toString();
    }

    /**
     * @param features need not adhere to any dimension restrictions.
     */
    public Adj(LL<ProductIndex> features) {
      features3 = features;
    }

    /**
     * @param features need not adhere to any dimension restrictions.
     */
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

    /**
     * @param reindex says whether features should be taken mod dimension.
     */
    public Adj(int[] features, boolean reindex) {
      assert reindex || numInterceptFeatures == 0
          : "did you account for intercept features in your indexing (in addition to mod dimension)?";
      this.features = features;
      if (reindex) {
        for (int i = 0; i < features.length; i++)
          features[i] = reindex(features[i]);
      }
    }

    private int reindex(long rawIndex) {
      if (rawIndex < 0)
        rawIndex = -rawIndex;
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

    private double featL2Norm() {
      double l;
      if (features3 != null)
        l = features3.length;
      else if (features != null)
        l = features.length;
      else if (features2 != null)
        l = features2.size();
      else
        throw new RuntimeException();
      return Math.sqrt(l);
    }

    @Override
    public void backwards(double dErr_dForwards) {
      if (passiveAgressive)
        dErr_dForwards /= featL2Norm();
      if (features3 != null) {
        for (LL<ProductIndex> pi = features3; pi != null; pi = pi.cdr())
          bwh(reindex(pi.car().getProdFeature()), dErr_dForwards);
      } else if (features != null) {
        for (int i = 0; i < features.length; i++)
          bwh(features[i], dErr_dForwards);
      } else {
        for (ProductIndex pi : features2)
          bwh(reindex(pi.getProdFeature()), dErr_dForwards);
      }
      COUNTER_BACKWARDS++;
    }
  }

  public static void main(String[] args) {
    Random rand = new Random(9001);
    for (int D : Arrays.asList(1<<10, 1<<14, 1<<18, 1<<22, 1<<26)) {  // dimension
      for (int n : Arrays.asList(1<<10, 1<<12, 1<<14, 1<<16, 1<<18)) {    // num instances
        Log.info("starting D=" + D + " n=" + n);

        // Params that generate the labels
        AveragedPerceptronWeights wActual = new AveragedPerceptronWeights(D, 0);
        for (int i = 0; i < D; i++)
          wActual.w.set(i, rand.nextGaussian());

        // Maintain an explicit average
        IntDoubleDenseVector wavg = new IntDoubleDenseVector(D);

        // Do some learning
        AveragedPerceptronWeights w = new AveragedPerceptronWeights(D, 0);
        boolean convertToArray = false;
        int k = D / 5;   // features per instance
        for (int i = 0; i < n; i++) {
          List<ProductIndex> f = new ArrayList<>();
          for (int j = 0; j < k; j++) f.add(new ProductIndex(rand.nextInt(D), D));
          double y = wActual.score(f, convertToArray).forwards();
          Adjoints yhat = w.score(f, convertToArray);
          double a = y * yhat.forwards();
          if (a <= 0) {
            yhat.backwards(1);
          }
          w.completedObservation();
          wavg.add(w.w);
        }
        wavg.scale(1d / n);

        // Check that the averages are the same
        for (int i = 0; i < D; i++) {
          double a = w.getAveragedWeight(i);
          double b = wavg.get(i);
          System.out.println(i + ": actual=" + a + " expected=" + b);
          assert Math.abs(a - b) < 1e-8;
        }
      }
    }
  }
}
