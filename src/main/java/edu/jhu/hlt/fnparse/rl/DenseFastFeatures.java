package edu.jhu.hlt.fnparse.rl;

public class DenseFastFeatures implements Params {

  class Adj implements Adjoints {
    private double[] features;
    private Action action;
    public Adj(double[] f, Action a) {
      features = f;
      action = a;
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
  }

  private double[] theta;
  private double learningRate;

  public DenseFastFeatures() {
    theta = new double[4];
    learningRate = 0.05d;
  }

  @Override
  public Adjoints score(State s, Action a) {
    double[] f = new double[theta.length];
    f[0] = a.mode == Action.COMMIT ? 1d : 0d;
    f[1] = a.mode == Action.COMMIT_AND_PRUNE_OVERLAPPING ? 1d : 0d;
    f[2] = a.hasSpan() ? 1d : 0d;
    f[3] = !a.hasSpan() ? 1d : 0d;
    // TODO more
    return new Adj(f, a);
  }

  @Override
  public void update(Adjoints a, double reward) {
    Adj adj = (Adj) a;
    for (int i = 0; i < theta.length; i++) {
      theta[i] += learningRate * reward * adj.features[i];
    }
  }
}
