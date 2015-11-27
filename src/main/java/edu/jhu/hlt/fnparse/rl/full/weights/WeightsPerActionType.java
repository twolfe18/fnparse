package edu.jhu.hlt.fnparse.rl.full.weights;

import edu.jhu.hlt.fnparse.rl.full.State.AT;

public class WeightsPerActionType extends WeightsMatrix<AT> {

  @Override
  public int numRows() {
    return AT.values().length;
  }

  @Override
  public int row(AT t) {
    return t.ordinal();
  }
}
