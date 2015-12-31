package edu.jhu.hlt.fnparse.rl.full;

import edu.jhu.hlt.fnparse.rl.full2.AbstractTransitionScheme;

public interface SearchCoefficients {
  public GeneralizedCoef coefLoss();
  public GeneralizedCoef coefModel();
  public GeneralizedCoef coefRand();

  default public double forwards(StepScores<?> scores) {
    double a = coefModel().forwards(scores);
    double b = coefRand().forwards(scores);
    double c = coefLoss().forwards(scores);
    double d = a + b + c;
    assert !Double.isNaN(d);
    assert !AbstractTransitionScheme.CHECK_FOR_FINITE_SCORES || Double.isFinite(d);
    return d;
  }

  default public void backwards(StepScores<?> scores, double dErr_dForwards) {
    coefModel().backwards(scores, dErr_dForwards);
    coefRand().backwards(scores, dErr_dForwards);
    coefLoss().backwards(scores, dErr_dForwards);
  }
}
