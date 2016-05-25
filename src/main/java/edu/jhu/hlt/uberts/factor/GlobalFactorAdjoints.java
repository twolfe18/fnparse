package edu.jhu.hlt.uberts.factor;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;

/**
 * When re-scoring items on the agenda with global features, we will often need
 * to know details of the features which have already been added to them, rather
 * than just seeing a flat {@link Adjoints}. This class lets us de-structure
 * the global+local scores.
 *
 * @author travis
 */
public class GlobalFactorAdjoints implements Adjoints {
  NumArgs.Adj numArgs;
  Adjoints argLocAllArgs;
  Adjoints argLocPairwise;
  Adjoints roleCooc;
  Adjoints localScore;

  public GlobalFactorAdjoints(Adjoints localScore) {
    this.localScore = localScore;
  }

  @Override
  public double forwards() {
    double f = localScore.forwards();
    if (numArgs != null)
      f += numArgs.forwards();
    if (argLocAllArgs != null)
      f += argLocAllArgs.forwards();
    if (argLocPairwise != null)
      f += argLocPairwise.forwards();
    if (roleCooc != null)
      f += roleCooc.forwards();
    return f;
  }

  @Override
  public void backwards(double dErr_dForwards) {
    Log.info("global backwards");
    localScore.backwards(dErr_dForwards);
    if (numArgs != null)
      numArgs.backwards(dErr_dForwards);
    if (argLocAllArgs != null)
      argLocAllArgs.backwards(dErr_dForwards);
    if (argLocPairwise != null)
      argLocPairwise.backwards(dErr_dForwards);
    if (roleCooc != null)
      roleCooc.backwards(dErr_dForwards);
  }

  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("(GFAdj local=" + localScore);
    if (numArgs != null)
      sb.append(" numArgs=" + numArgs);
    if (argLocAllArgs != null)
      sb.append(" argLoc1=" + argLocAllArgs);
    if (argLocPairwise != null)
      sb.append(" argLoc2=" + argLocPairwise);
    if (roleCooc != null)
      sb.append(" roleCooc=" + roleCooc);
    return sb.toString();
  }
}