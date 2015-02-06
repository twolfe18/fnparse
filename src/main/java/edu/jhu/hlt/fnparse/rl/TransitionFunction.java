package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

import com.google.common.collect.Iterables;

import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.params.Params;

public interface TransitionFunction {

  public Iterable<Action> nextStates(StateSequence s);

  /**
   * Should be called every time an Action from nextStates is viewed.
   * If Adjoints were computed for to that Action, they should be passed into
   * this method. If not, null should be passed in.
   */
  default public void observeAdjoints(Adjoints scoredAction) {
    // no-op
  }


  /**
   * Calls COMMIT first, then passes all of these actions off to PRUNE.
   *
   * RULE: This instance must observe Adjoints (features/scores) for every
   * COMMIT Action (even if it has to be null -- that is why you pass in model
   * parameters to this constructor).
   *
   * In the event that bFunc forces ForwardSearch to skip a COMMIT action,
   * this instance needs to have the ability to compute the model parameters
   * for these Actions as needed by subsequent PRUNE Actions.
   */
  public static class Tricky implements TransitionFunction {
    public static final Logger LOG = Logger.getLogger(Tricky.class);
    private Actions useOnce;  // Iterable<Action> for the StateSequence provided at each call of nextStates()
    private Params.Stateful model;
    public Tricky(Params.Stateful model) {
      this.useOnce = null;
      this.model = model;
    }
    @Override
    public Iterable<Action> nextStates(StateSequence ss) {
      State s = ss.getCur();
      ActionIndex ai = ss.getActionIndex();
      List<Action> commits = (List<Action>) ActionType.COMMIT.next(s);
      useOnce = this.new Actions(s, ai, commits);
      return useOnce;
    }
    @Override
    public void observeAdjoints(Adjoints scoredAction) {
      useOnce.observeAdjoints(scoredAction);
    }

    /**
     * Takes an a bunch of COMMIT actions up front and deals them out first.
     * While this is happening (this is a co-routine with ForwardSearch.run),
     * this object receives the Adjoints representing the scores of the COMMIT
     * actions just dealt out. Once all of these COMMIT Adjoints have been collected
     * (and the COMMIT Actions have all been dealt out), this object calls
     * ActionType.PRUNE.next(State,FNParse,commitActions) to get the rest of the
     * prune Action/Adjoints!
     */
    class Actions implements Iterable<Action>, Iterator<Action> {
      private State state;
      private ActionIndex ai;
      private List<Action> commitActions;
      private List<Adjoints> commitAdjoints;
      private int commitActionsPtr; // points at the next elem of commitActions to pop
      private List<PruneAdjoints> prunes;
      private int prunesPtr;  // points at the next elem of prunes to pop

      public Actions(State state, ActionIndex ai, List<Action> commitActions) {
        this.state = state;
        this.ai = ai;
        this.commitActions = commitActions;
        this.commitAdjoints = new ArrayList<>();
        this.commitActionsPtr = 0;
        this.prunesPtr = 0;
      }

      public void buildPruneActions() {
        assert prunes == null;
        assert commitActionsPtr == commitActions.size();
        assert commitActions.size() == commitAdjoints.size();
        prunes = ActionType.PRUNE.next(state, commitAdjoints);
      }

      @Override
      public boolean hasNext() {
        boolean hn = commitActionsPtr < commitActions.size()
            || prunes == null || prunesPtr < prunes.size();
        if (!hn) {
          // This will be called before the last call to observeAdjoints
          LOG.info("[Tricky.Actions hasNext] doesn't have next!");
        }
        return hn;
      }

      @Override
      public Action next() {
        if (commitActionsPtr < commitActions.size()) {
          assert prunesPtr == 0;
          Action c = commitActions.get(commitActionsPtr++);
          return c;
        } else {
          if (commitActionsPtr == commitActions.size()) {
            buildPruneActions();
            commitActionsPtr++; // So that we only do this once
          }
          return prunes.get(prunesPtr++);
        }
      }

      /**
       * Once the model has score the COMMIT Action, the Adjoints should be passed
       * into this method. This should happen very soon after a call to next().
       */
      public void observeAdjoints(Adjoints commitScores) {
        if (commitAdjoints.size() < commitActions.size()) {
          assert prunesPtr == 0 && prunes == null;
          if (commitScores == null) {
            //LOG.info("[Trick.Actions observeAdjoints] computing adjoints because they were not provided");
            Action a = commitActions.get(commitActionsPtr - 1);
            commitScores = model.score(state, ai, a);
          }
          commitAdjoints.add(commitScores);
        }
        // else these are Adjoints for a PRUNE Action
      }

      @Override
      public Iterator<Action> iterator() {
        return this;
      }
    }
  }

  /**
   * Calls next on a specified set of ActionTypes and stitches them together.
   */
  public static class ActionDrivenTransitionFunction implements TransitionFunction {

    private ActionType[] actionTypes;

    public ActionDrivenTransitionFunction(ActionType... actionTypes) {
      this.actionTypes = actionTypes;
    }

    @Override
    public Iterable<Action> nextStates(StateSequence s) {
      State st = s.getCur();
      int n = actionTypes.length;
      List<Iterable<Action>> inputs = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        Iterable<Action> ita = actionTypes[i].next(st);
        inputs.add(ita);
      }
      return Iterables.concat(inputs);
    }
  }
}
