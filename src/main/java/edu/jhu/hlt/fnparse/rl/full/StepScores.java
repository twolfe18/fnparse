package edu.jhu.hlt.fnparse.rl.full;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.tutils.scoring.MutedAdjoints;

/**
 * Keeps track of model score, loss, and randomness thoughout search. Implements
 * Adjoints for easy interface with search. The separation is useful for things
 * like computing the margin and debugging.
 *
 * This is basically the same as {@link MutedAdjoints}, but allows well-structured
 * introspection.
 */
public class StepScores<T extends HowToSearch> implements Adjoints {

  private final T info;
  private final Adjoints model;
  private final MaxLoss loss;    // NOTE: This should be taken from Node2.loss
  private final double rand;
  private final StepScores<T> prev;

  @Override
  public String toString() {
    return String.format(
        "(Score forwards=%.1f"
        + " model=%s*%s"
        + " maxLoss=%s*%s"
        + " rand=%s*%.1f"
        + ") -> %s",
        forwards(),
        info.coefModel().shortString(), model,
        info.coefLoss().shortString(), loss,
        info.coefRand().shortString(), rand,
        prev);
  }

  /**
   * Provide the non-cumulative version of model/loss/rand.
   * This class memoizes calls to the cumulative query methods for model/loss/rand.
   */
  public StepScores(T info, Adjoints model, MaxLoss loss, double rand, StepScores<T> prev) {
    this.info = info;
    this.model = model;
    this.loss = loss;
    this.rand = rand;
    this.prev = prev;
  }

  public T getInfo() {
    return info;
  }

  /*
   * In what way does MaxLoss accumulate?
   * You must accumulate over *disjoint* sets!
   * PrefixLL must never call MaxLoss.sum since intersect(parent,child)=child
   * LLML calls MaxLoss.sum...
   * OK good, this is only called for pruned and eggs, where it is appropriate.
   *
   * Considering rule: StepScores is not recursive.
   * Its components (Adjoints,MaxLoss,double) may be cumulative, but all the
   * wiring up must be done by constructors of StepScores.
   *
   * I think the biggest problem is that, e.g. a T node needs to know its
   * MaxLoss (via StepScores currently). The only way to get this MaxLoss is
   * from aggregating children+pruned.
   *
   * At Node2 creation (in genEggs):
   * numPossible is given by user
   * numDetermined,fp,fn are 0 (and thereafter determined by sum(map(determined, pruned ++ children))
   */

  public MaxLoss getLoss() {
    return loss;
  }

  public double getCumulativeModelScore() {
    if (Double.isNaN(__msMemo)) {
      __msMemo = model.forwards();
      if (prev != null)
        __msMemo += prev.getCumulativeModelScore();
    }
    return __msMemo;
  }
  private double __msMemo = Double.NaN;

  /** Cumulative, info.coefs `dot` [model, loss, rand] */
  @Override
  public double forwards() {
    if (Double.isNaN(__forwardsMemo)) {
      double s = 0;
      if (!info.coefModel().iszero() && !info.coefModel().muteForwards)
        s += info.coefModel().coef * model.forwards();
      if (!info.coefLoss().iszero() && !info.coefLoss().muteForwards)
        s += info.coefLoss().coef * -loss.maxLoss();
      if (!info.coefRand().iszero() && !info.coefRand().muteForwards)
        s += info.coefRand().coef * rand;
      if (prev != null)
        s += prev.forwards();
      __forwardsMemo = s;
    }
    return __forwardsMemo;
  }
  private double __forwardsMemo = Double.NaN;

  @Override
  public void backwards(double dErr_dForwards) {
    if (!info.coefModel().iszero())
      model.backwards(info.coefModel().coef * dErr_dForwards);
    // rand and loss don't need to have backwards run.
    if (prev != null)
      prev.backwards(dErr_dForwards);
  }

}
