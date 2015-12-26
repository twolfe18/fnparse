package edu.jhu.hlt.fnparse.rl.full;

import java.io.Serializable;

public abstract class GeneralizedCoef implements Serializable {
  private static final long serialVersionUID = -8325375378258659099L;

  public final double coef;
  public final boolean muteForwards;

  public GeneralizedCoef(double coef, boolean muteForwards) {
    if (Double.isNaN(coef))
      throw new IllegalArgumentException();
    if (Double.isInfinite(coef))
      throw new RuntimeException("not tested yet");
    this.coef = coef;
    this.muteForwards = muteForwards;
  }

  public abstract double forwards(StepScores<?> modelLossRand);
  public abstract void backwards(StepScores<?> modelLossRand, double dErr_dForwards);

  public static final GeneralizedCoef ZERO = new GeneralizedCoef(0, false) {
    private static final long serialVersionUID = 4090671970176060117L;
    @Override
    public double forwards(StepScores<?> modelLossRand) {
      return 0;
    }
    @Override
    public void backwards(StepScores<?> modelLossRand, double dErr_dForwards) {
      // no-op
    }
  };

  public static class Rand extends GeneralizedCoef {
    private static final long serialVersionUID = -1157381510101736075L;
    public Rand(double coef) {
      super(coef, false);
    }
    @Override
    public double forwards(StepScores<?> modelLossRand) {
      return coef * modelLossRand.getRand();
    }
    @Override
    public void backwards(StepScores<?> modelLossRand, double dErr_dForwards) {
      // no-op
    }
  }

  public static class Model extends GeneralizedCoef {
    private static final long serialVersionUID = -6443024647803364059L;

    public Model(double coef, boolean muteForwards) {
      super(coef, muteForwards);
    }

    @Override
    public double forwards(StepScores<?> modelLossRand) {
      if (muteForwards)
        return 0;
      if (coef == 0)
        return 0;
      return coef * modelLossRand.getModel().forwards();
    }

    @Override
    public void backwards(StepScores<?> modelLossRand, double dErr_dForwards) {
      if (coef != 0)
        modelLossRand.getModel().backwards(coef * dErr_dForwards);
    }
  }

  public String shortString() {
    return String.format("(%.1f mute=%s)", coef, muteForwards);
  }

  @Override
  public String toString() {
    return String.format("(GenCoef %.2f muteForwards=%s)", coef, muteForwards);
  }

  public static class Loss extends GeneralizedCoef {
    private static final long serialVersionUID = -4336010038231374048L;

    public static enum Mode {
      MIN_LOSS,
      MAX_LOSS,
      MAX_LOSS_LIN,
      MAX_LOSS_POW,
    }

    public final Mode mode;
    public final double beta;

    public Loss(double coef, Mode m, double b) {
      super(coef, false);
      mode = m;
      beta = b;
    }

    public double forwards(StepScores<?> modelLossRand) {
      MaxLoss l = modelLossRand.getLoss();
      if (muteForwards)
        return 0;
      if (coef == 0)
        return 0;
      switch (mode) {
      case MIN_LOSS:
        return coef * l.minLoss();
      case MAX_LOSS:
        return coef * l.maxLoss();
      case MAX_LOSS_LIN:
        return coef * l.linMaxLoss(beta);
      case MAX_LOSS_POW:
        return coef * l.powMaxLoss(beta);
      default:
        throw new RuntimeException();
      }
    }

    @Override
    public void backwards(StepScores<?> modelLossRand, double dErr_dForwards) {
      // no-op
    }
  }
}