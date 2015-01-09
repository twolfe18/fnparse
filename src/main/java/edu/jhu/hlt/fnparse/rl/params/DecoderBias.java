package edu.jhu.hlt.fnparse.rl.params;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.rl.Action;

/**
 * This is a type of Param which adds a value to actions that result in a
 * nullSpan. This is not used in regular training, which optimizes Hamming loss,
 * but rather is only imposed at test time, and has its single parameter which
 * is set by line search on the actual loss function (F1) after primary learning
 * has occurred, likely on other data.
 * 
 * @author travis
 */
public class DecoderBias implements Params.Stateless {

  private double recallBias = 0d;

  public void setRecallBias(double b) {
    this.recallBias = b;
  }

  public double getRecallBias() {
    return recallBias;
  }

  @Override
  public void update(Adjoints a, double reward) {
    // No-op. You don't learn these Params like other params.
  }

  @Override
  public Adjoints score(FNTagging f, Action a) {
    double score = a.hasSpan() ? recallBias : 0d;
    return new Adjoints.Explicit(score, a, getClass().getName());
  }
}
