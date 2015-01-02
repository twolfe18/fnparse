package edu.jhu.hlt.fnparse.rl;

/**
 * Wraps some params and adds epsilon * |state.committed|
 * 
 * To have an additive prior on steps that complete a sequence, set epsilon > 0.
 * To have an additive prior on undo actions, set epsilon < 0.
 *
 * @author travis
 */
public class NumCommittedParamsWrapper implements Params {
  private double epsilon;
  private Params wrapped;

  public NumCommittedParamsWrapper(double epsilon, Params wrapped) {
    this.epsilon = epsilon;
    this.wrapped = wrapped;
  }

  private final class Adjoints implements edu.jhu.hlt.fnparse.rl.Adjoints {
    private double committed;
    private edu.jhu.hlt.fnparse.rl.Adjoints wrapped;
    public Adjoints(double committed, edu.jhu.hlt.fnparse.rl.Adjoints wrapped) {
      this.committed = committed;
      this.wrapped = wrapped;
    }
    @Override
    public double getScore() {
      return wrapped.getScore() + epsilon * committed;
    }
    @Override
    public Action getAction() {
      return wrapped.getAction();
    }
  }

  @Override
  public Adjoints score(State s, Action a) {
    int nc = s.numCommitted();
    edu.jhu.hlt.fnparse.rl.Adjoints w = wrapped.score(s, a);
    return new Adjoints(nc, w);
  }

  @Override
  public void update(edu.jhu.hlt.fnparse.rl.Adjoints a, double reward) {
    wrapped.update(a, reward);
  }
}
