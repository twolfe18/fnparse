package edu.jhu.hlt.fnparse.rl.full;

public interface SearchCoefficients {
  public GeneralizedCoef coefLoss();
  public GeneralizedCoef coefModel();
  public GeneralizedCoef coefRand();

  default public double forwards(StepScores<?> scores) {
    return coefModel().forwards(scores)
        + coefRand().forwards(scores)
        + coefLoss().forwards(scores);
  }

  default public void backwards(StepScores<?> scores, double dErr_dForwards) {
    coefModel().backwards(scores, dErr_dForwards);
    coefRand().backwards(scores, dErr_dForwards);
    coefLoss().backwards(scores, dErr_dForwards);
  }
}
