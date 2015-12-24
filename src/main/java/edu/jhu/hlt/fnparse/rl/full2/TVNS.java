package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.fnparse.rl.full.SearchCoefficients;
import edu.jhu.hlt.fnparse.rl.full.StepScores;
import edu.jhu.hlt.tutils.scoring.Adjoints;

/**
 * "S" for score, which includes rand, which is a perturbation on the score. I
 * considered hiding rand within the model score, but since I still need
 * {@link StepScores} and {@link SearchCoefficients}, I might as well keep it
 * as is.
 *
 * @author travis
 */
public class TVNS extends TVN {
  private Adjoints model;
  private double rand;

  public TVNS(int type, int value, int numPossible, int goldMatching,
      long prime, Adjoints model, double rand) {
    super(type, value, numPossible, goldMatching, prime);
    this.model = model;
    this.rand = rand;
  }

  public Adjoints getModel() {
    return model;
  }

  public double getRand() {
    return rand;
  }
}
