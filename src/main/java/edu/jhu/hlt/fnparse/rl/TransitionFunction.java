package edu.jhu.hlt.fnparse.rl;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer;
import edu.jhu.hlt.fnparse.util.FakeIterable;

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

    // These are particular to each call of nextStates
    // The first three are needed to compute scores when ForwardSearch skips the
    // model score due to bFunc.
    private Params.Stateful model;
    private State state;
    private SpanIndex<Action> previousActions;
    private Actions allActionIter;

    // Used to compute tau
    private Params.PruneThreshold tauParams;

    // If true, only build PRUNE actions which apply to all spans for a (t,k)
    private boolean onlySimplePrunes = true;

    // If true, PruneAdjoints.forwards
    //    = tau.forwards - max_{c : COMMIT pruned by this prune} c.forwards
    // otherwise PruneAdjoints.forwards = tau.forwards
    private boolean useCommitScore = false;

    public Tricky(Params.Stateful model, Params.PruneThreshold tauParams) {
      this.model = model;
      this.tauParams = tauParams;
    }

    @Override
    public Iterable<Action> nextStates(StateSequence ss) {
      state = ss.getCur();
      previousActions = ss.getActionIndex();
      List<Action> commits = (List<Action>) ActionType.COMMIT.next(state);
      if (commits.isEmpty()) {
        // This can happen at the end of search
        state = null;
        previousActions = null;
        allActionIter = null;
        return Collections.emptyList();
      }
      allActionIter = this.new Actions(commits);
      return new FakeIterable<>(allActionIter);
    }

    @Override
    public void observeAdjoints(Adjoints scoredAction) {
      allActionIter.observeAdjoints(scoredAction);
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
    class Actions implements Iterator<Action> {

      // Stores scores of COMMIT actions, either from observeAdjoints or from
      // computing them within this class.
      private ActionSpanIndex<Adjoints.HasSpan> commitAdjoints;

      // COMMIT actions and the state of the iterator through them.
      private List<Action> commitActions;
      private int commitActionsPtr;

      // PRUNE actions and the state of the iterator through them.
      private List<PruneAdjoints> prunes;
      private int prunesPtr;

      public Actions(List<Action> commitActions) {
        if (commitActions.isEmpty())
          throw new IllegalArgumentException();
        this.commitActions = commitActions;
        this.commitActionsPtr = 0;
        this.prunesPtr = 0;
        int n = state.getSentence().size();
        if (useCommitScore)
          this.commitAdjoints = new ActionSpanIndex.SpaceEfficient<>(n);
        else
          this.commitAdjoints = new ActionSpanIndex.None<>();
      }

      @Override
      public boolean hasNext() {
        boolean hn = commitActionsPtr < commitActions.size()
            || prunes == null || prunesPtr < prunes.size();
        if (!hn && RerankerTrainer.PRUNE_DEBUG) {
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
            assert prunes == null;
            assert commitActionsPtr == commitActions.size();
            prunes = ActionType.PRUNE.next(state, commitAdjoints, tauParams, onlySimplePrunes);

            if (prunes.size() == 0) {
              prunes = ActionType.PRUNE.next(state, commitAdjoints, tauParams, onlySimplePrunes);
            }
            assert prunes.size() > 0 : "no prunes?";

            assert !useCommitScore || commitActions.size() == commitAdjoints.size();
            if (RerankerTrainer.PRUNE_DEBUG) {
              int n = state.getSentence().size();
              LOG.info("[Trick.Actions] useCommitScore=" + useCommitScore
                  + " nWords=" + n
                  + " nCommits=" + commitActions.size()
                  + " nPrunes=" + prunes.size());
            }

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
        if (!useCommitScore)
          return;
        if (commitAdjoints.size() < commitActions.size()) {
          assert prunesPtr == 0 && prunes == null;
          if (commitScores == null) {
            //LOG.info("[Trick.Actions observeAdjoints] computing adjoints because they were not provided");
            Action a = commitActions.get(commitActionsPtr - 1);
            commitScores = model.score(state, previousActions, a);
          }
          commitAdjoints.mutableUpdate(new Adjoints.HasSpan(commitScores));
        }
        // else these are Adjoints for a PRUNE Action
      }
    }
  }
}
