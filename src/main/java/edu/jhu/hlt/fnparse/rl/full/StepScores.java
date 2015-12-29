package edu.jhu.hlt.fnparse.rl.full;

import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
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
        new Adjoints.Sum(a.model, b.model),
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
    assert Double.isFinite(rand) && !Double.isNaN(rand);
    this.info = info;
    this.model = model;
    this.loss = loss;
    this.rand = rand;
  }

  public StepScores<T> plusModel(Adjoints score) {
    return new StepScores<>(info, new Adjoints.Sum(score, model), loss, rand);
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

//  /** Cumulative, info.coefs `dot` [model, loss, rand] */
////  @Override
//  public double forwardsMax() {
//    if (Double.isNaN(__forwardsMemo)) {
//      double s = 0;
//      if (!info.coefModel().iszero() && !info.coefModel().muteForwards)
//        s += info.coefModel().coef * model.forwards();
//      if (!info.coefLoss().iszero() && !info.coefLoss().muteForwards)
//        s += info.coefLoss().coef * -loss.maxLoss();
//      if (!info.coefRand().iszero() && !info.coefRand().muteForwards)
//        s += info.coefRand().coef * rand;
//      __forwardsMemo = s;
//    }
//    return __forwardsMemo;
//  }
//  private double __forwardsMemo = Double.NaN;
//
//
//  /** Other one uses maxLoss, this uses minLoss */
//  public double forwardsMin() {
//    if (Double.isNaN(__forwardsMemo2)) {
//      double s = 0;
//      if (!info.coefModel().iszero() && !info.coefModel().muteForwards)
//        s += info.coefModel().coef * model.forwards();
//      if (!info.coefLoss().iszero() && !info.coefLoss().muteForwards)
//        s += info.coefLoss().coef * -loss.minLoss();
//      if (!info.coefRand().iszero() && !info.coefRand().muteForwards)
//        s += info.coefRand().coef * rand;
//      __forwardsMemo2 = s;
//    }
//    return __forwardsMemo2;
//  }
//  private double __forwardsMemo2 = Double.NaN;
//
//
//  public double forwardsH() {
//    if (Double.isNaN(__forwardsMemo3)) {
//      double s = 0;
//      if (!info.coefModel().iszero() && !info.coefModel().muteForwards)
//        s += info.coefModel().coef * model.forwards();
//      if (!info.coefLoss().iszero() && !info.coefLoss().muteForwards)
//        s += info.coefLoss().coef * -loss.hLoss();
//      if (!info.coefRand().iszero() && !info.coefRand().muteForwards)
//        s += info.coefRand().coef * rand;
//      __forwardsMemo3 = s;
//    }
//    return __forwardsMemo3;
//  }
//  private double __forwardsMemo3 = Double.NaN;
//
//
//  public double forwardsMaxLin() {
//    if (Double.isNaN(__forwardsMemo4)) {
//      double beta = 0.25;
//      double s = 0;
//      if (!info.coefModel().iszero() && !info.coefModel().muteForwards)
//        s += info.coefModel().coef * model.forwards();
//      if (!info.coefLoss().iszero() && !info.coefLoss().muteForwards)
//        s += info.coefLoss().coef * -loss.linMaxLoss(beta);
//      if (!info.coefRand().iszero() && !info.coefRand().muteForwards)
//        s += info.coefRand().coef * rand;
//      __forwardsMemo4 = s;
//    }
//    return __forwardsMemo4;
//  }
//  private double __forwardsMemo4 = Double.NaN;
//
//  public double forwardsMaxPow() {
//    if (Double.isNaN(__forwardsMemo5)) {
//      double beta = 0.5;
//      double s = 0;
//      if (!info.coefModel().iszero() && !info.coefModel().muteForwards)
//        s += info.coefModel().coef * model.forwards();
//      if (!info.coefLoss().iszero() && !info.coefLoss().muteForwards)
//        s += info.coefLoss().coef * -loss.powMaxLoss(beta);
//      if (!info.coefRand().iszero() && !info.coefRand().muteForwards)
//        s += info.coefRand().coef * rand;
//      __forwardsMemo5 = s;
//    }
//    return __forwardsMemo5;
//  }
//  private double __forwardsMemo5 = Double.NaN;


//  @Override
//  public void backwards(double dErr_dForwards) {
//    if (!info.coefModel().iszero())
//      model.backwards(info.coefModel().coef * dErr_dForwards);
//    // rand and loss don't need to have backwards run.
//  }

}
