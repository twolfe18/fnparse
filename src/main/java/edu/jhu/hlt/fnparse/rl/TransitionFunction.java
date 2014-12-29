package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.State.StateSequence;

public interface TransitionFunction {

  public Iterable<StateSequence> previousStates(StateSequence s);
  public Iterable<StateSequence> nextStates(StateSequence s);

  /**
   * Only supports COMMIT actions over atomic spans.
   */
  public static class Simple implements TransitionFunction {

    /**
     * If not null, then all next state transitions must be consistent with y.
     */
    private FNParse y;

    public Simple(FNParse goal) {
      this.y = goal;
    }

    @Override
    public Iterable<StateSequence> previousStates(StateSequence s) {
      List<StateSequence> ss = new ArrayList<>();
      State st = s.getCur();
      int T = st.numFrameInstance();
      for (int t = 0; t < T; t++) {
        int K = st.getFrame(t).numRoles();
        for (int k = 0; k < K; k++) {
          Span a = st.committed(t, k);
          if (a == null) continue;
          // Make an action that would have lead to this (t,k) being committed
          Action act = new Action(t, k, Action.COMMIT, new ASpan(a));
          ss.add(new StateSequence(null, s, null, act));
        }
      }
      return ss;
    }

    @Override
    public Iterable<StateSequence> nextStates(StateSequence s) {
      List<StateSequence> ss = new ArrayList<>();
      State st = s.getCur();
      int T = st.numFrameInstance();
      for (int t = 0; t < T; t++) {
        int K = st.getFrame(t).numRoles();
        for (int k = 0; k < K; k++) {
          Span a = st.committed(t, k);
          if (a != null) continue;
          if (y == null) {
            // Consider all possible actions
            for (Span arg : st.allowableSpans(t, k)) {
              Action act = new Action(t, k, Action.COMMIT, new ASpan(arg));
              ss.add(new StateSequence(s, null, null, act));
            }
          } else {
            // Only consider actions that will lead to y (there is only one)
            Span yArg = y.getFrameInstance(t).getArgument(k);
            Action act = new Action(t, k, Action.COMMIT, new ASpan(yArg));
            ss.add(new StateSequence(s, null, null, act));
          }
        }
      }
      return ss;
    }
  }
}
