package edu.jhu.hlt.fnparse.rl.params;

import java.util.Collection;

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
  public Adjoints score(FNTagging f, Action a) {
    double score = a.hasSpan() ? recallBias : 0d;
    return new Adjoints.Explicit(score, a, getClass().getName());
  }

  @Override
  public <T extends HasUpdate> void update(Collection<T> batch) {
    // No-op. You don't learn these Params like other params.
  }

  @Override
  public void doneTraining() {
    LOG.warn("this should probably never be called because this should be "
        + "added after hamminTrain");
    assert false;
  }
}
