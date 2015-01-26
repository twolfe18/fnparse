package edu.jhu.hlt.fnparse.rl.params;

import java.util.function.Supplier;

import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleVector;

/**
 * Wraps the result of a forward pass, which is often needed for computing a
 * gradient.
 *
 * @author travis
 */
public interface Adjoints {

  public Action getAction();  // TODO put in sub-class

  public double forwards();

  public void backwards(double dScore_dForwards);

  // TODO
  // the fact that getUpdate(double[] addTo, double scale), from HasUpdate
  // is here reflects the assumption that Adjoints produce rather than apply
  // updates. This seems wrong, Adjoints should be able to apply a backwards
  // update.
  // Like Params.update!

  /**
   * Replaces Dense and Sparse
   */
  public static class Vector implements Adjoints {
    private final Action action;
    private final IntDoubleVector features;
    private final IntDoubleVector weights;  // not owned by this class
    private double score;
    private boolean computed;
    public Vector(Action a, double[] weights, double[] features) {
      this(a, new IntDoubleDenseVector(weights), new IntDoubleDenseVector(features));
    }
    public Vector(Action a, IntDoubleVector weights, IntDoubleVector features) {
      this.action = a;
      this.weights = weights;
      this.features = features;
      this.computed = false;
    }
    @Override
    public Action getAction() {
      return action;
    }
    @Override
    public double forwards() {
      if (!computed) {
        score = features.dot(weights);
        computed = true;
      }
      return score;
    }
    @Override
    public void backwards(double dScore_dForwards) {
      assert computed;
      features.apply(new FnIntDoubleToDouble() {
        @Override
        public double call(int i, double f_i) {
          weights.add(i, dScore_dForwards * f_i);
          return f_i;
        }
      });
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

  /*
   * Represents a score parameterized by a dot product between parameters and
   * a dense vector.
  public static class DenseFeatures implements Adjoints {
    protected double[] features;
    private double[] theta;
    private Action action;
    public DenseFeatures(double[] features, double[] theta, Action a) {
      if (theta.length != features.length)
        throw new IllegalArgumentException();
      this.action = a;
      this.features = features;
      this.theta = theta;
    }
    @Override
    public double getScore() {
      double s = 0d;
      for (int i = 0; i < features.length; i++)
        s += features[i] * theta[i];
      return s;
    }
    @Override
    public Action getAction() {
      return action;
    }
    @Override
    public void getUpdate(double[] addTo, double scale) {
      for (int i = 0; i < theta.length; i++)
        addTo[i] += scale * features[i];
    }
  }
   */

  /*
   * Represents a score parameterized by a dot product between parameters and
   * a sparse vector.
  public static class SparseFeatures implements Adjoints {
    private FeatureVector features;
    private double[] theta;
    private Action action;
    public SparseFeatures(FeatureVector features, double[] theta, Action a) {
      action = a;
      this.features = features;
      this.theta = theta;
    }
    public void add(int index, double value) {
      features.add(index, value);
    }
    @Override
    public double getScore() {
      return features.dot(theta);
    }
    @Override
    public Action getAction() {
      return action;
    }
    @Override
    public void getUpdate(double[] addTo, double scale) {
      features.apply(new FnIntDoubleToDouble() {
        @Override
        public double call(int arg0, double arg1) {
          addTo[arg0] += scale * arg1;
          return arg1;
        }
      });
    }
  }
   */
}
