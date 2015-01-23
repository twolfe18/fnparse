package edu.jhu.hlt.fnparse.rl.params;

import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.State;

public class DenseFastFeatures implements Params.Stateful {

  private double[] theta;

  public DenseFastFeatures() {
    theta = new double[4];
  }

  @Override
  public Adjoints score(State s, Action a) {
    double[] f = new double[theta.length];
    f[0] = a.mode == 0 ? 1d : 0d;
    f[1] = a.mode == 1 ? 1d : 0d;
    f[2] = a.hasSpan() ? 1d : 0d;
    f[3] = !a.hasSpan() ? 1d : 0d;
    // TODO more
    return new Adjoints.Vector(a, theta, f);
  }

  @Override
  public void doneTraining() {
    LOG.info("[doneTraining] currently doesn't support weight averaging");
  }
}
