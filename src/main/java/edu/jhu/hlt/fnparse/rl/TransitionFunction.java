package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Span;

public interface TransitionFunction {

  public Iterable<StateSequence> previousStates(StateSequence s);
  public Iterable<StateSequence> nextStates(StateSequence s);

  /*
   * AH, BE CAREFUL NOT TO PUT TOO MUCH IN THIS CLASS.
   * Below is an example of a bad idea.
   * The true solution is to allow different action type other than COMMIT,
   * e.g. ones that prune spans ending in IN.
   * 
   * DONT-TODO make a parametric transition function.
   * When this happens, the call below to State.naiveAllowableSpans will be
   * replaced by something that returns Adjoints (corresponding to the pruning
   * decision).
   * How to handle Adjoints for actions that never make it through pruning?
   * Could say that any action that is taken also comes with the adjoints of
   * everything that was pruned from its context, so if that decision turns out
   * to be wrong the pruning adjoints will get their weights reversed.
   * Need to think out how this would work for positive credit for pruning...
   * Or just put this in a more general RL context...
   */

  /**
   * Only supports COMMIT actions over atomic spans.
   */
  public static class Simple implements TransitionFunction {

    /**
     * If not null, then all next state transitions must be consistent with y.
     */
    private FNParse y;
    private Params params;

    public Simple(FNParse goal, Params params) {
      this.y = goal;
      this.params = params;
    }

    @Override
    public Iterable<StateSequence> previousStates(StateSequence s) {
      List<StateSequence> ss = new ArrayList<>();
      State st = s.getCur();
      if (st == null) {
        throw new IllegalArgumentException(
            "you must provide a StateSequence with a current state");
      }
      int T = st.numFrameInstance();
      for (int t = 0; t < T; t++) {
        int K = st.getFrame(t).numRoles();
        for (int k = 0; k < K; k++) {
          Span a = st.committed(t, k);
          if (a == null) continue;
          // Make an action that would have lead to this (t,k) being committed
          Action act = new Action(t, k, a);
          Adjoints adj = params.score(st, act);
          ss.add(new StateSequence(null, s, null, adj));
        }
      }
      return ss;
    }

    @Override
    public Iterable<StateSequence> nextStates(StateSequence s) {
      List<StateSequence> ss = new ArrayList<>();
      State st = s.getCur();
      if (st == null) {
        throw new IllegalArgumentException(
            "you must provide a StateSequence with a current state");
      }
      int T = st.numFrameInstance();
      for (int t = 0; t < T; t++) {
        int K = st.getFrame(t).numRoles();
        for (int k = 0; k < K; k++) {
          Span a = st.committed(t, k);
          if (a != null) continue;
          if (y == null) {
            // Consider all possible actions
            for (Span arg : st.naiveAllowableSpans(t, k)) {
              Action act = new Action(t, k, arg);
              Adjoints adj = params.score(st, act);
              // TODO decide on if score should take the leaving state or arriving state
              ss.add(new StateSequence(s, null, null, adj));
            }
          } else {
            // Only consider actions that will lead to y (there is only one)
            Span yArg = y.getFrameInstance(t).getArgument(k);
            Action act = new Action(t, k, yArg);
            Adjoints adj = params.score(st, act);
            ss.add(new StateSequence(s, null, null, adj));
          }
        }
      }
      return ss;
    }
  }
}
