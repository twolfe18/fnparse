package edu.jhu.hlt.fnparse.rl.full;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.LabelIndex;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.rl.full.State.Info;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;

/**
 * Don't ask me why its called FModel. This is is a wrapper around {@link State}.
 *
 * @author travis
 */
public class FModel {

  private Info oracleInf;
  private Info mvInf;
  private Info decInf;

  public Update getUpdate(FNParse y) {
    oracleInf.label
      = mvInf.label
      = new LabelIndex(y);
    oracleInf.setTargetPruningToGoldLabels(mvInf);
    boolean includeGoldSpansIfMissing = true;
    DeterministicRolePruning.Mode mode = DeterministicRolePruning.Mode.XUE_PALMER_HERMANN;
    oracleInf.setArgPruningUsingSyntax(mode, includeGoldSpansIfMissing, mvInf);

    // TODO Compute loss
    final double loss = 0;
    final State oracleState = State.runInference(oracleInf);
    final State mvState = State.runInference(mvInf);
    final double hinge = Math.max(0, mvState.score.forwards() + loss - oracleState.score.forwards());
    return new Update() {
      @Override
      public double apply(double learningRate) {
        if (hinge > 0) {
          oracleState.score.backwards(-learningRate);
          mvState.score.backwards(+learningRate);
        }
        return hinge;
      }
      @Override
      public double violation() {
        return hinge;
      }
    };
  }
}
