package edu.jhu.hlt.fnparse.rl.full;

import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full2.AbstractTransitionScheme;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.tutils.scoring.MutedAdjoints;

/**
 * Keeps track of model score, loss, and randomness thoughout search. Implements
 * Adjoints for easy interface with search. The separation is useful for things
 * like computing the margin and debugging.
 *
 * This is basically the same as {@link MutedAdjoints}, but allows well-structured
 * introspection.
 *
 * @param T TODO Remove this. I used to use this to figure out how to do
 * (model,loss,rand) -> Adjoints, but now {@link DoubleBeam} has {@link HowToSearch}
 * which does this. I'll keep info:T around for now, but it probably isn't necessary.
 */
public class StepScores<T> {

  private final T info;
  private final Adjoints model;
  private final MaxLoss loss;    // NOTE: This should be taken from Node2.loss
  private final double rand;

  /**
   * info:T must match and MaxLosses must be over disjoint sets.
   */
  public static StepScores<?> sum(StepScores<?> a, StepScores<?> b) {
    if (a.info != b.info) {
      // consider extending this to .equals(), but should probably match == for efficiency
      throw new IllegalArgumentException("infos must match!");
    }
    return new StepScores<>(a.info,
        Adjoints.cacheSum(a.model, b.model),
        MaxLoss.sum(a.loss, b.loss),
        a.rand + b.rand);
  }

  @Override
  public String toString() {
    return String.format(
        "(Score info=%s model=%s maxLoss=%s rand=%.2f)",
        info, model, loss, rand);
  }

  public StepScores(T info, Adjoints model, MaxLoss loss, double rand) {
    assert model != null;
    assert loss != null;
    assert !AbstractTransitionScheme.CHECK_FOR_FINITE_SCORES || Double.isFinite(rand);
    assert !Double.isNaN(rand);
    this.info = info;
    this.model = Adjoints.cacheIfNeeded(model);
    this.loss = loss;
    this.rand = rand;
  }

  public StepScores<T> plusModel(Adjoints score) {
    return new StepScores<>(info, Adjoints.cacheSum(score, model), loss, rand);
  }

  public T getInfo() {
    return info;
  }

  public Adjoints getModel() {
    return model;
  }

  public MaxLoss getLoss() {
    return loss;
  }

  public double getRand() {
    return rand;
  }
}
