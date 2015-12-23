package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.fnparse.rl.full.StepScores;

public class LLSS extends LL<HasStepScores> {
  protected StepScores<?> scoreSum;

  public LLSS(HasStepScores item, LLSS next) {
    super(item, next);
    if (next == null)
      scoreSum = item.getStepScores();
    else
      scoreSum = StepScores.sum(item.getStepScores(), next.scoreSum);
  }

  public StepScores<?> getScoreSum() {
    return scoreSum;
  }
}
