package edu.jhu.hlt.fnparse.rl;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;

public class PruneAdjoints extends Action implements Adjoints {

  private List<Adjoints> commitActions;
  private Adjoints tau;
  private double score = Double.NaN;

  public PruneAdjoints(int t, int k, int mode, int start, int end) {
    super(t, k, mode, start, end);
  }

  @Override
  public Action getAction() {
    return this;
  }

  // Just a link to impl
  public boolean prunes(int t, int k, Span arg) {
    return ActionType.PRUNE.isPrunedBy(t, k, arg, this);
  }

  /**
   * Before this method is called, this is just an Action.
   * @param tau is the parameters that control the offset for the pruning score.
   * @param commitActions is the set of COMMIT actions that are pruned by this action.
   */
  public void turnIntoAdjoints(Adjoints tau, List<Adjoints> commitActions) {
    this.tau = tau;
    this.commitActions = commitActions;
  }

  @Override
  public double forwards() {
    if (tau == null)
      throw new IllegalStateException("call turnIntoAdjoints first");
    if (Double.isNaN(score)) {
      double thresh = tau.forwards();
      double maxCommScore = 0;
      for (int i = 0; i < commitActions.size(); i++) {
        Adjoints ai = commitActions.get(i);
        double ais = ai.forwards();
        if (i == 0 || ais > maxCommScore)
          maxCommScore = ais;
      }
      score = thresh - maxCommScore;
    }
    return score;
  }

  @Override
  public void backwards(double dScore_dForwards) {
    if (tau == null)
      throw new IllegalStateException("call turnIntoAdjoints first");
    tau.backwards(dScore_dForwards);
  }
}
