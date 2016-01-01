package edu.jhu.hlt.fnparse.rl.full;

import java.io.Serializable;

public abstract class GeneralizedCoef implements Serializable {
  private static final long serialVersionUID = -8325375378258659099L;

  protected final double coefForwards;
  protected final double coefBackwards;

  public GeneralizedCoef(double coefForwards, double coefBackwards) {
    if (Double.isNaN(coefForwards) || Double.isInfinite(coefForwards))
      throw new IllegalArgumentException();
    if (Double.isNaN(coefBackwards) || Double.isInfinite(coefBackwards))
      throw new IllegalArgumentException();
    this.coefForwards = coefForwards;
    this.coefBackwards = coefBackwards;
  }

  public abstract double forwards(StepScores<?> modelLossRand);
  public abstract void backwards(StepScores<?> modelLossRand, double dErr_dForwards);

  public static final GeneralizedCoef ZERO = new GeneralizedCoef(0, 0) {
    private static final long serialVersionUID = -3214870986068010924L;
    @Override
    public double forwards(StepScores<?> modelLossRand) {
      return 0;
    }
    @Override
    public void backwards(StepScores<?> modelLossRand, double dErr_dForwards) {
    }
  };

  public static class Rand extends GeneralizedCoef {
    private static final long serialVersionUID = -1157381510101736075L;
    public Rand(double coef) {
      super(coef, 0);
    }
    @Override
    public double forwards(StepScores<?> modelLossRand) {
      return coefForwards * modelLossRand.getRand();
    }
    @Override
    public void backwards(StepScores<?> modelLossRand, double dErr_dForwards) {
      // no-op
    }
  }

  public static class Model extends GeneralizedCoef {
    private static final long serialVersionUID = -6443024647803364059L;

    public Model(double coef, boolean updateTowards) {
      super(coef, updateTowards ? +1 : -1);
    }

    @Override
    public double forwards(StepScores<?> modelLossRand) {
      if (coefForwards == 0)
        return 0;
      return coefForwards * modelLossRand.getModel().forwards();
    }

    @Override
    public void backwards(StepScores<?> modelLossRand, double dErr_dForwards) {
      if (coefBackwards != 0)
        modelLossRand.getModel().backwards(coefBackwards * dErr_dForwards);
    }
  }

  public String shortString() {
    return String.format("(f=%.1f b=%s)", coefForwards, coefBackwards);
  }

  @Override
  public String toString() {
    return String.format("(GenCoef forwards=%.2f backwards=%.2f)", coefForwards, coefBackwards);
  }

  public static class Loss extends GeneralizedCoef {
    private static final long serialVersionUID = -4336010038231374048L;

    public static enum Mode {
      H_LOSS,
      MIN_LOSS,
      MAX_LOSS,
      MAX_LOSS_LIN,
      MAX_LOSS_POW,
    }

    public final Mode mode;
    public final double beta;

    public Loss(double coef, Mode m, double b) {
      super(coef, 0);
      mode = m;
      beta = b;
    }

    @Override
    public double forwards(StepScores<?> modelLossRand) {
      MaxLoss l = modelLossRand.getLoss();
      if (coefForwards == 0)
        return 0;
      switch (mode) {
      case H_LOSS:
        return coefForwards * l.hLoss();
      case MIN_LOSS:
        return coefForwards * l.minLoss();
      case MAX_LOSS:
        return coefForwards * l.maxLoss();
      case MAX_LOSS_LIN:
        return coefForwards * l.linMaxLoss(beta);
      case MAX_LOSS_POW:
        return coefForwards * l.powMaxLoss(beta);
      default:
        throw new RuntimeException();
      }
    }

    @Override
    public String toString() {
      return String.format("(GC.Loss forwards=%.2f mode=%s backwards=%.2f)", coefForwards, mode, coefBackwards);
    }

    @Override
    public void backwards(StepScores<?> modelLossRand, double dErr_dForwards) {
      // no-op
    }

    /** Doesn't respect {@link Loss#mode}, uses I(delta min loss > 0, -inf, 0) */
    public static class Oracle extends Loss {
      private static final long serialVersionUID = -2669067902555041859L;
      public Oracle() {
        super(0, Mode.MIN_LOSS, 1);
      }
      @Override
      public double forwards(StepScores<?> modelLossRand) {
        MaxLoss l = modelLossRand.getLoss();
        if (l.fn > 0)
          return Double.NEGATIVE_INFINITY;
        // TODO Accounting where spurious FPs are introduced by pruning non-leaves?
        // We shouldn't have to worry about spurious FPs causing the oracle to
        // do the wrong thing, because an spurious hatch will always outscore a
        // bad squash (-inf).
        return -l.fp;
      }
      @Override
      public String toString() {
        return "(CoefLoss ORACLE)";
      }
    }
  }
}