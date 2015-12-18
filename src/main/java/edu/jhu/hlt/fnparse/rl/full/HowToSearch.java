package edu.jhu.hlt.fnparse.rl.full;

import edu.jhu.hlt.fnparse.rl.full.State.GeneralizedCoef;

public interface HowToSearch {
  public GeneralizedCoef coefLoss();
  public GeneralizedCoef coefModel();
  public GeneralizedCoef coefRand();
  public int beamSize();        // How many states to keep at every step
  public int numConstraints();  // How many states to keep for forming margin constraints (a la k-best MIRA)
}