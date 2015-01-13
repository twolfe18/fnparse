package edu.jhu.hlt.fnparse.rl.params;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;

/**
 * wraps the result of a forward pass, which is often needed for computing a
 * gradient.
 */
public interface Adjoints extends HasUpdate {

  public double getScore();
  public Action getAction();
  //public void update(double reward, double learningRate, AveragedWeights addTo);

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
    public double getScore() {
      return score;
    }
    @Override
    public Action getAction() {
      return action;
    }
    @Override
    public void getUpdate(double[] addTo, double scale) {
      // no-op
    }
  }

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
//    public void update(double reward, double learningRate) {
//      double s = reward * learningRate;
//      for (int i = 0; i < theta.length; i++)
//        theta[i] += s * features[i];
//    }
    @Override
    public void getUpdate(double[] addTo, double scale) {
      for (int i = 0; i < theta.length; i++)
        addTo[i] += scale * features[i];
    }
  }

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
//    public void update(double reward, double learningRate) {
//      features.apply(new FnIntDoubleToDouble() {
//        private final double s = learningRate * reward;
//        @Override
//        public double call(int arg0, double arg1) {
//          theta[arg0] += s * arg1;
//          return arg1;
//        }
//      });
//    }
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
}
