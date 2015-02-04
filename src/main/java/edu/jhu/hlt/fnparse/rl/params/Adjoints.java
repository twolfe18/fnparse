package edu.jhu.hlt.fnparse.rl.params;

import java.util.function.Supplier;

import org.apache.log4j.Logger;

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


  /**
   * Replaces Dense and Sparse
   */
  public static class Vector implements Adjoints {
    private final Action action;
    private final IntDoubleVector features;
    private final IntDoubleVector weights;  // not owned by this class
    private final double l2Penalty;
    private final double learningRate;
    private double score;
    private boolean computed;
    public Vector(Action a, double[] weights, double[] features, double l2Penalty, double learningRate) {
      this(a, new IntDoubleDenseVector(weights), new IntDoubleDenseVector(features), l2Penalty, learningRate);
    }
    public Vector(Action a, IntDoubleVector weights, IntDoubleVector features, double l2Penalty, double learningRate) {
      this.action = a;
      this.weights = weights;
      this.features = features;
      this.computed = false;
      this.l2Penalty = l2Penalty;
      this.learningRate = learningRate;
    }
    @Override
    public String toString() {
      return "(Adjoints.Vector " + action + ")";
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
          double g = dScore_dForwards * f_i;
          double l2p = 0d;
          if (l2Penalty > 0)
            l2p = weights.get(i) * l2Penalty;
          double u = learningRate * (g - l2p);
          weights.add(i, u);
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
}
