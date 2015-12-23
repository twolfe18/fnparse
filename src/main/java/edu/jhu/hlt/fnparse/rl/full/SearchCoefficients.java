package edu.jhu.hlt.fnparse.rl.full;

import edu.jhu.hlt.fnparse.rl.full.State.GeneralizedCoef;

public interface SearchCoefficients {
  public GeneralizedCoef coefLoss();
  public GeneralizedCoef coefModel();
  public GeneralizedCoef coefRand();
}
