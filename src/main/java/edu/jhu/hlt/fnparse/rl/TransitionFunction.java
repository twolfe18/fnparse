package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.List;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.params.Params;

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
  
  public static class ActionDrivenTransitionFunction implements TransitionFunction {
    
    // It would be nice if that code that applies updates is right next to the
    // code that generates next states.
    // Interested parties:
    // 1) Action              -- stores the type of action)
    // 2) State               -- contains apply() method
    // 3) TransitionFunction  -- generates Actions
    
    // Proposal: use the BitSet possible as the interface between everyone.
    // Action2 has the following methods
    // 1) apply :: Action -> BitSet -> BitSet   -- updates a BitSet
    // 2) next :: BitSet -> [Action]            -- list of do moves
    // 3) prev :: BitSet -> [Action]            -- list of undo moves
    
    // This is certainly more elegant, but would it be less efficient?
    // Right now: TransitionFunction knows all and loops over the BitSet once,
    // times each potential action (which is only COMMIT now).
    // Under this proposal, there would be one loop over the BitSet per action
    // type, which is maybe less efficient (if you could determine whether many
    // action types are allowable by only querying the BitSet once -- which
    // would make it a single pass for all action types). In all likelihood
    // though, this code would be too ugly and I would make a few passes anyway.
    
    // Organizing the code this way has the added benefit of being able to add
    // as many new actions as you want, and not needing to update any apply
    // or generate transition code.

    private ActionType[] actionTypes;
    private Params.Stateful theta;
    private FNParse y;

    /**
     * @deprecated Should not really need y, use the constructor without it.
     */
    public ActionDrivenTransitionFunction(Params.Stateful theta, FNParse y, ActionType... actionTypes) {
      this.actionTypes = actionTypes;
      this.theta = theta;
      this.y = y;
    }

    public ActionDrivenTransitionFunction(Params.Stateful theta, ActionType... actionTypes) {
      this.actionTypes = actionTypes;
      this.theta = theta;
      this.y = null;
    }

    @Override
    public Iterable<StateSequence> previousStates(StateSequence s) {
      State st = s.getCur();
      int n = actionTypes.length;
      List<Iterable<StateSequence>> inputs = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        Iterable<Action> ita = actionTypes[i].prev(st);
        Iterable<StateSequence> itss = Iterables.transform(ita, new Function<Action, StateSequence>() {
          @Override
          public StateSequence apply(Action input) {
            Adjoints adj = theta.score(st, input);
            //Adjoints adj = new Adjoints.Lazy(() -> theta.score(st, input));
            
            // lskdjflkds
            // how was this working before?
            
            
            return new StateSequence(null, s, null, adj);
          }
        });
        inputs.add(itss);
      }
      return Iterables.concat(inputs);
    }

    @Override
    public Iterable<StateSequence> nextStates(StateSequence s) {
      // Lift Actions to StateSequences,
      // then concatenate each actionType's Iterable into one.
      State st = s.getCur();
      int n = actionTypes.length;
      List<Iterable<StateSequence>> inputs = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        Iterable<Action> ita = actionTypes[i].next(st, y);
        Iterable<StateSequence> itss = Iterables.transform(ita, new Function<Action, StateSequence>() {
          @Override
          public StateSequence apply(Action input) {
            //Adjoints adj = theta.score(st, input);
            Adjoints adj = new Adjoints.Lazy(() -> theta.score(st, input));
            return new StateSequence(s, null, null, adj);
          }
        });
        inputs.add(itss);
      }
      return Iterables.concat(inputs);
    }
  }

  /**
   * Only supports COMMIT actions over atomic spans.
   * @deprecated use ActionDrivenTransitionFunction
   */
  public static class Simple implements TransitionFunction {

    /**
     * If not null, then all next state transitions must be consistent with y.
     */
    private FNParse y;
    private Params.Stateful params;

    public Simple(FNParse goal, Params.Stateful params) {
      this.y = goal;
      this.params = params;
      assert false : "don't use this anymore";
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
          Action act = new Action(t, k, ActionType.COMMIT.getIndex(), a);
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
              Action act = new Action(t, k, ActionType.COMMIT.getIndex(), arg);
              Adjoints adj = params.score(st, act);
              // TODO decide on if score should take the leaving state or arriving state
              ss.add(new StateSequence(s, null, null, adj));
            }
          } else {
            // Only consider actions that will lead to y (there is only one)
            Span yArg = y.getFrameInstance(t).getArgument(k);
            Action act = new Action(t, k, ActionType.COMMIT.getIndex(), yArg);
            Adjoints adj = params.score(st, act);
            ss.add(new StateSequence(s, null, null, adj));
          }
        }
      }
      return ss;
    }
  }
}
