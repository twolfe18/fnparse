package edu.jhu.hlt.fnparse.rl.params;

import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.State;

public class DenseFastFeatures implements Params.Stateful {

  private double[] theta;
  private double learningRate;

  public DenseFastFeatures() {
    theta = new double[4];
    learningRate = 0.05d;
  }

  @Override
  public Adjoints score(State s, Action a) {
    double[] f = new double[theta.length];
    f[0] = a.mode == 0 ? 1d : 0d;
    f[1] = a.mode == 1 ? 1d : 0d;
    f[2] = a.hasSpan() ? 1d : 0d;
    f[3] = !a.hasSpan() ? 1d : 0d;
    // TODO more
    return new Adjoints.DenseFeatures(f, theta, a);
  }

  @Override
  public void update(Adjoints a, double reward) {
    ((Adjoints.DenseFeatures) a).update(reward, learningRate);
  }
}
