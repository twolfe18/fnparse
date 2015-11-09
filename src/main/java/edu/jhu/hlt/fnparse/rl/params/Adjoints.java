package edu.jhu.hlt.fnparse.rl.params;

import java.util.Iterator;
import java.util.Random;
import java.util.function.Supplier;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.prim.map.IntDoubleEntry;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;
import edu.jhu.prim.vector.IntDoubleVector;

/**
 * Wraps the result of a forward pass, which is often needed for computing a
 * gradient.
 *
 * @author travis
 */
public interface Adjoints {

  public Action getAction();  // TODO put in sub-class

  /**
   * Compute the circuit's value when run forwards.
   */
  public double forwards();

  /**
   * Apply an update to the parameters given this partial derivative.
   * NOTE: This method MAY NOT DEPEND ON the current value of the parameters!
   * If we produce N Adjoints when a training example is observed, any
   * permutation of those N adjoints should yield the same parameter values.
   */
  public void backwards(double dScore_dForwards);


  /**
   * For when you need Adjoints to extend HasSpan.
   * ...and more generally when you want your Adjoints to look like an Action.
   */
  public static class HasSpan implements Adjoints, edu.jhu.hlt.fnparse.util.HasSpan {
    private Adjoints wrapped;
    public HasSpan(Adjoints a) {
      this.wrapped = a;
    }
    public int getT() {
      return wrapped.getAction().t;
    }
    public int getK() {
      return wrapped.getAction().k;
    }
    @Override
    public Span getSpan() {
      Action a = wrapped.getAction();
      if (a.hasSpan()) {
        Span s = a.getSpan();
        assert s.end >= 0;
        return s;
      }
      throw new RuntimeException("be more careful");
    }
    @Override
    public Action getAction() {
      return wrapped.getAction();
    }
    @Override
    public double forwards() {
      return wrapped.forwards();
    }
    @Override
    public void backwards(double dScore_dForwards) {
      wrapped.backwards(dScore_dForwards);
    }
  }

  public static class LazyL2UpdateVector {
    public final IntDoubleDenseVector weights;
    private int numUpdatesDeferred;
    private int updateInterval;

    public LazyL2UpdateVector(IntDoubleDenseVector weights, int updateInterval) {
      this.weights = weights;
      this.numUpdatesDeferred = 0;
      this.updateInterval = updateInterval;
    }

    public void maybeApplyL2Reg(double lambda) {
      numUpdatesDeferred++;
      if (numUpdatesDeferred == updateInterval) {
        double p = Math.pow(1 - lambda, numUpdatesDeferred);
        weights.scale(p);
        numUpdatesDeferred = 0;
      }
    }

    public void set(int index, double value) {
      weights.set(index, value);
    }

    public void set(double[] weights) {
      this.weights.scale(0);
      for (int i = 0; i < weights.length; i++)
        this.weights.set(i, weights[i]);
    }

    public void scale(double scale) {
      weights.scale(scale);
    }

    public double[] makeCopyOfWeights(int dimension) {
      int D = weights.getNumExplicitEntries();
      assert D <= dimension;
      double[] w = new double[dimension];
      for (int i = 0; i < D; i++)
        w[i] = weights.get(i);
      return w;
    }
  }

  /**
   * Replaces Dense and Sparse
   */
  public static class Vector implements Adjoints {
    public static final Logger LOG = Logger.getLogger(Vector.class);
    public static final boolean PARANOID = false;
    public static final FnIntDoubleToDouble VECTOR_VALUE_CHECK = new FnIntDoubleToDouble() {
      @Override
      public double call(int arg0, double arg1) {
        if (Double.isInfinite(arg1) || Double.isNaN(arg1))
          throw new RuntimeException();
        return arg1;
      }
    };

    // The Params that gave birth to these Adjoints
    private final Object parent;

    private final Action action;

    private final IntDoubleVector features;
    private final LazyL2UpdateVector weights;  // not owned by this class

    private double score;
    private boolean computed;

    private final double l2Penalty;

//    public Vector(Object parent, Action a, double[] weights, double[] features, double l2Penalty) {
//      this(parent, a, new LazyL2Update(new IntDoubleDenseVector(weights), 32), new IntDoubleDenseVector(features), l2Penalty);
//    }

    public Vector(Object parent, Action a, LazyL2UpdateVector weights, IntDoubleVector features, double l2Penalty) {
      this.parent = parent;
      this.action = a;
      this.weights = weights;
      this.features = features;
      this.computed = false;
      this.l2Penalty = l2Penalty;
      if (PARANOID) {
//        weights.apply(VECTOR_VALUE_CHECK);
        features.apply(VECTOR_VALUE_CHECK);
      }
    }

    @Override
    public String toString() {
      if (parent != null) {
        return String.format("(Adjoints.Vector score=%+.2f parent=%s %s)",
            forwards(), parent.getClass().getSimpleName(), action);
      }
      return String.format("(Adjoints.Vector score=%+.2f %s)", forwards(), action);
    }

    @Override
    public Action getAction() {
      return action;
    }

    @SuppressWarnings("unused")
    @Override
    public double forwards() {
      if (!computed) {
        score = features.dot(weights.weights);
        computed = true;
        if (PARANOID && (Double.isInfinite(score) || Double.isNaN(score)))
          throw new RuntimeException();
      }
      return score;
    }

    @Override
    public void backwards(double dScore_dForwards) {
      assert Double.isFinite(dScore_dForwards) && !Double.isNaN(dScore_dForwards);
      assert computed;
//      if (parent != null && parent instanceof FeatureParams) {
//        LOG.info("[backwards] parent: " + System.identityHashCode(parent) + " features:" + ((FeatureParams) parent).describeFeatures(features));
//        //LOG.info("check me!");
//      }
      // Only do the l2Penalty update every k iterations for efficiency.
      if (l2Penalty > 0) {
        weights.maybeApplyL2Reg(l2Penalty);
        if (PARANOID)
          weights.weights.apply(VECTOR_VALUE_CHECK);
      }
      if (features instanceof IntDoubleUnsortedVector) {
        // Matt... how is it possible that there is no way to expose this functionality
        // (iterate without sorting entries) without reflection...
        Iterator<IntDoubleEntry> itr = ((IntDoubleUnsortedVector) features).iterator();
        while (itr.hasNext()) {
          IntDoubleEntry i = itr.next();
          int idx = i.index();
          double f_i = i.get();
          double u = dScore_dForwards * f_i;
          assert Double.isFinite(u) && !Double.isNaN(u);
          weights.weights.add(idx, u);
        }
      } else {
        features.apply(new FnIntDoubleToDouble() {
          @Override
          public double call(int idx, double f_i) {
            double u = dScore_dForwards * f_i;
            assert Double.isFinite(u) && !Double.isNaN(u);
            weights.weights.add(idx, u);
            return f_i;
          }
        });
      }
      if (PARANOID) {
        features.apply(VECTOR_VALUE_CHECK);
        weights.weights.apply(VECTOR_VALUE_CHECK);
      }
      //LOG.info("weight=" + weights);
    }
  }

  /**
   * Must provide a Supplier<Adjoints> that returns a non-null Adjoints.
   */
  public static class Lazy implements Adjoints {
    private Supplier<Adjoints> thunk;
    private Adjoints value;
    public Lazy(Supplier<Adjoints> thunk) {
      this.thunk = thunk;
      this.value = null;
    }
    @Override
    public String toString() {
      return "(Lazy " + value + ")";
    }
    @Override
    public double forwards() {
      if (value == null) {
        value = thunk.get();
        assert value != null;
      }
      return value.forwards();
    }
    @Override
    public Action getAction() {
      if (value == null) {
        value = thunk.get();
        assert value != null;
      }
      return value.getAction();
    }
    @Override
    public void backwards(double dScore_dForwards) {
      if (value == null) {
        value = thunk.get();
        assert value != null;
      }
      value.backwards(dScore_dForwards);
    }
  }

  /**
   * No parameters (to be updated) -- just a partial score to be added in.
   */
  public static class Explicit implements Adjoints {
    private String tag;     // for storing providence info if desired
    private double score;
    private Action action;
    public Explicit(double score, Action action) {
      this(score, action, null);
    }
    public Explicit(double score, Action action, String tag) {
      this.tag = tag;
      this.score = score;
      this.action = action;
    }
    public String getTag() {
      return tag;
    }
    public String toString() {
      return String.format("(Explicit %s%.1f %s)",
          tag == null ? "" : tag + " ", score, action);
    }
    @Override
    public double forwards() {
      return score;
    }
    @Override
    public Action getAction() {
      return action;
    }
    @Override
    public void backwards(double dScore_dForwards) {
      // no-op
    }
  }


  public static IntDoubleVector testSpeedOfL2Reg(double l2Penalty, Random rand, int skip) {
    int nt = 1500;
    int nnz = 200;
    int dim = 2 * 1024 * 1024;
    LazyL2UpdateVector weights = new LazyL2UpdateVector(new IntDoubleDenseVector(dim), skip);

    long start = System.currentTimeMillis();
    for (int i = 0; i < nt; i++) {
      // Draw a random vector
      IntDoubleUnsortedVector fv = new IntDoubleUnsortedVector();
      for (int j = 0; j < nnz; j++)
        fv.add(rand.nextInt(dim), 1);

      // Do a gradient
      Adjoints.Vector a = new Adjoints.Vector(null, null, weights, fv, l2Penalty);
      a.backwards(rand.nextGaussian());
    }
    double time = (System.currentTimeMillis() - start) / 1000d;
    System.out.println("time=" + time + " nnz=" + nnz + " dim=" + dim + " l2Penalty=" + l2Penalty);
    return weights.weights;
  }

  public static void foo(double l2Penalty) {
    testSpeedOfL2Reg(0, new Random(9001), 1);
    IntDoubleVector w1 = testSpeedOfL2Reg(l2Penalty, new Random(9001), 1);
    IntDoubleVector w2 = testSpeedOfL2Reg(l2Penalty, new Random(9001), 32);
    w2.scale(-1);
    w1.add(w2);
    double sqErr = w1.getL2Norm();
    System.out.println("sqError=" + sqErr);
  }

  public static void main(String[] args) {
    foo(1e-8);
    foo(1e-8);
    foo(1e-8);
    foo(1e-6);
    foo(1e-6);
    foo(1e-6);
    foo(1e-4);
    foo(1e-4);
    foo(1e-4);
  }
}
