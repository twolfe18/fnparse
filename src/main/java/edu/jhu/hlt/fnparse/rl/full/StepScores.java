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

  public static final class MaxLoss {
    public final int numPossible;
    public final int numDetermined;
    public final int fp;
    public final int fn;

    /** if a and b represent disjoint sets, this returns a MaxLoss representing their union */
    public static MaxLoss sum(MaxLoss a, MaxLoss b) {
      return new MaxLoss(
          a.numPossible + b.numPossible,
          a.numDetermined + b.numDetermined,
          a.fp + b.fp,
          a.fn + b.fn);
    }

    public MaxLoss(int numPossible) {
      this(numPossible, 0, 0, 0);
    }

    public MaxLoss(int numPossible, int numDetermined, int fp, int fn) {
      assert numPossible > 0;
      assert numDetermined <= numPossible;
      assert fp + fn <= numDetermined;
      this.numPossible = numPossible;
      this.numDetermined = numDetermined;
      this.fp = fp;
      this.fn = fn;
    }

    public int maxLoss() {
      return (numPossible - numDetermined) + fp + fn;
    }

    public MaxLoss fp() {
      return new MaxLoss(numPossible, numDetermined+1, fp+1, fn);
    }

    public MaxLoss fn() {
      return new MaxLoss(numPossible, numDetermined+1, fp, fn+1);
    }

    @Override
    public String toString() {
      return "((N=" + numPossible + " - D=" + numDetermined + ") + fp=" + fp + " fn=" + fn + ")";
    }
  }

  // TODO Either remove StepScores<Info> (fn-specific) from State2 (general) or
  // remove Info (fn-specific) from StepScores<Info> [probably the latter].
//    public final Info info;
  public final T info;

  public final Adjoints model;
//  public final int lossFP, lossFN;
//  public final int numPossible, numDetermined;  // aka N and D
////    public final int trueP, trueN;
  public final MaxLoss loss;
  public final double rand;
  public final StepScores<T> prev;

//    public static <T extends HowToSearch> StepScores<T> zero(T info) {
//      return new StepScores<>(info, Adjoints.Constant.ZERO, 0, 0, 0, 0, 0, null);
//    }

  @Override
  public String toString() {
    return String.format(
//          "(Score forwards=%.1f consObj=%.1f"
        "(Score forwards=%.1f"
        + " model=%s*%s"
////          + " loss=%s*(fp=%d fn=%d tp=%d tn=%d)"
//        + " maxLoss=%s*(N=%d - D=%d + fp=%d fn=%d)"
        + " maxLoss=%s*%s"
        + " rand=%s*%.1f"
        + ") -> %s",
        forwards(), //constraintObjectivePlusConstant(),
        info.coefModel().shortString(), model,
////          info.coefLoss().shortString(), lossFP, lossFN, trueP, trueN,
//        info.coefLoss().shortString(), numPossible, numDetermined, lossFP, lossFN,
        info.coefLoss().shortString(), loss,
        info.coefRand().shortString(), rand,
        prev);
  }

//    public StepScores(T info, Adjoints model,
//        HammingLoss loss, double rand, StepScores<T> prev) {
//      this(info, model, loss.getFP(), loss.getFN(), loss.getTP(), loss.getTN(), rand, prev);
//    }

  public StepScores(T info, Adjoints model,
      MaxLoss loss,
//      int lossFP, int lossFN,
//      int numPossible, int numDetermined,
////        int trueP, int trueN,
      double rand, StepScores<T> prev) {
//    if (lossFP < 0 || lossFN < 0)
//      throw new IllegalArgumentException();
    this.info = info;
    this.model = model;
    this.loss = loss;
//    this.lossFP = lossFP;
//    this.lossFN = lossFN;
//    this.numDetermined = numDetermined;
//    this.numPossible = numPossible;
////      this.trueP = trueP;
////      this.trueN = trueN;
    this.rand = rand;
    this.prev = prev;
//    assert numDetermined <= numPossible;
  }

  public T getInfo() {
    return info;
  }

  public MaxLoss getLoss() {
    return loss;
  }

//  /** Cumulative */
//  public double getHammingLoss() {
//    return lossFN + lossFP;
//  }

  /** Cumulative */
  public double getModelScore() {
    if (Double.isNaN(__msMemo)) {
      __msMemo = model.forwards();
      if (prev != null)
        __msMemo += prev.getModelScore();
    }
    return __msMemo;
  }
  private double __msMemo = Double.NaN;

//  public int maxLoss() {
//    return (numPossible - numDetermined) + lossFN + lossFP;
//  }
//    /**
//     * Margin constraints are formed by:
//     *   oracle.constraintObjectivePlusConstant() >= mostViolated.constraintObjectivePlusConstant()
//     *
//     * Note: max_{y \in Proj(z)} loss(y) = max_y loss(y) - \sum_i deltaLoss(i)
//     *       or "maxProjLoss = maxLossConstant - accumLoss"
//     *
//     * Note: Since we will be doing `Objective(MV) - Objective(oracle)`,
//     * which will both contain a manProjLoss value, and thus a maxLoss constant
//     * (by the identity above), we can use accumLoss to compute differences of
//     * Objective as: accumModel - accumLoss
//     * (This is true of oracle/mv and regardless of search heuristic)
//     */
//    public double constraintObjectivePlusConstant() {
////      return getModelScore() + trueP + trueN - (lossFN + lossFP);
//
////      double s = model.forwards() + trueP + trueN - (lossFN + lossFP);
//////      double s = model.forwards() + trueP - (lossFN + lossFP);
//
//      // Only different from forwards() is that this counts TP+TN
//      double s = 0;
//      if (!info.coefModel().iszero() && !info.coefModel().muteForwards)
//        s += info.coefModel().coef * model.forwards();
//      if (!info.coefLoss().iszero() && !info.coefLoss().muteForwards)
//        s += info.coefLoss().coef * -(lossFP + lossFN - (trueN + trueP));
//      if (!info.coefRand().iszero() && !info.coefRand().muteForwards)
//        s += info.coefRand().coef * rand;
//      if (prev != null)
//        s += prev.forwards();
//
//      if (prev != null)
//        s += prev.constraintObjectivePlusConstant();
//      return s;
//    }

  /** Search objectve, Cumulative */
  @Override
  public double forwards() {
    if (Double.isNaN(__forwardsMemo)) {
      double s = 0;
      if (!info.coefModel().iszero() && !info.coefModel().muteForwards)
        s += info.coefModel().coef * model.forwards();
      if (!info.coefLoss().iszero() && !info.coefLoss().muteForwards)
//          s += info.coefLoss().coef * -(lossFP + lossFN);
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
    if (!info.coefModel().iszero()) {
      /*
       * We are double parameterized with:
       * 1) the sign on the model score coefficient in search
       * 2) the fact that we do backwards(+/-hinge)
       */
      model.backwards(info.coefModel().coef * dErr_dForwards);
//        model.backwards(dErr_dForwards);
    }
    if (prev != null)
      prev.backwards(dErr_dForwards);
  }
}