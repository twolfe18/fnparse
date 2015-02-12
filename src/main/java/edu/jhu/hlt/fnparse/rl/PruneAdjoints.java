package edu.jhu.hlt.fnparse.rl;

import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer;
import edu.jhu.hlt.fnparse.util.HasSpan;

/**
 * An Action and Adjoints for an action generated by PRUNE. The reason that this
 * class implements both is that the core features (which live in Adjoints) of
 * the PRUNE actions should be how they were created, which is done when the
 * Actions are created.
 *
 * TODO I'm not really happy with how tau is handled. tau should contain this
 * "construction type" feature that I just mentioned, but it is awkward here.
 *
 * @author travis
 */
public class PruneAdjoints extends Action implements Adjoints, HasSpan {
  public static final Logger LOG = Logger.getLogger(PruneAdjoints.class);

  private List<? extends Adjoints> commitActions;
  private Adjoints tau;
  private int maxIndex;
  private double score;

  public PruneAdjoints(int t, int k, int mode, int start, int end) {
    super(t, k, mode, start, end);
    maxIndex = -1;
  }

  @Override
  public Action getAction() {
    return this;
  }

  // Just a link to impl
  public boolean prunes(int t, int k, Span arg) {
    return ActionType.PRUNE.isPrunedBy(t, k, arg, this);
  }

  // Just a link to impl
  public String toString() {
    //return ActionType.PRUNE.describe(this);
    return String.format("(PRUNE t=%d k=%d s=%+2f tau=%s)",
        t, k, forwards(), tau.getClass().getSimpleName());
  }

  /**
   * Before this method is called, this is just an Action.
   * @param tau is the parameters that control the offset for the pruning score.
   * @param commitActions is the set of COMMIT actions that are pruned by this action.
   */
  public void turnIntoAdjoints(Adjoints tau, List<? extends Adjoints> commitActions) {
    this.tau = tau;
    if (commitActions == null) {
      if (RerankerTrainer.PRUNE_DEBUG)
        LOG.info("[turnIntoAdjoints] not using score of COMMIT actions");
      this.commitActions = null;
    } else {
      if (commitActions.isEmpty()) {
        throw new IllegalArgumentException("Every PRUNE must get rid of at least "
            + "one possible item, and the only witness to that that I now of is "
            + "the existence of a COMMIT action for that item.\n"
            + "Plus, the score of this action doesn't make much sense if there "
            + "are no COMMIT Actions.");
      }
      this.commitActions = commitActions;
      if (RerankerTrainer.PRUNE_DEBUG) {
        LOG.info("[turnIntoAdjoints] " + toString()
            + " nCommitActions=" + commitActions.size());
      }
    }
  }

  @Override
  public double forwards() {
    if (tau == null)
      throw new IllegalStateException("call turnIntoAdjoints first");
    if (maxIndex < 0) {
      if (commitActions == null) {
        maxIndex = Integer.MAX_VALUE;
        score = tau.forwards();
      } else {
        double thresh = tau.forwards();
        double maxCommScore = 0;
        for (int i = 0; i < commitActions.size(); i++) {
          Adjoints ai = commitActions.get(i);
          double ais = ai.forwards();
          if (i == 0 || ais > maxCommScore) {
            maxIndex = i;
            maxCommScore = ais;
          }
        }
        score = thresh - maxCommScore;
      }
    }
    return score;
  }

  @Override
  public void backwards(double dScore_dForwards) {
    if (tau == null)
      throw new IllegalStateException("call turnIntoAdjoints first");
    if (maxIndex < 0)
      throw new IllegalStateException("call forwards first");
    tau.backwards(dScore_dForwards);
    if (commitActions != null) {
      Adjoints cScore = commitActions.get(maxIndex);
      cScore.backwards(-dScore_dForwards);
    }
  }
}
