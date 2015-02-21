package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer;

/**
 * A class representing a type of Action which provides the information needed
 * to plug into ForwardSearch/learning.
 *
 * RULE: Every Action must be monotonic in State.possible (i.e. it eliminates at
 * least one possible item) so that the search space is guaranteed to be finite.
 *
 * Previously, I had required (via this interface) that reverse search be
 * supported (by implementing prev() and unapply()), but this became inefficient
 * for pruning type actions (e.g. when unapplying a prune, you need to know if
 * a region you pruned had previously been pruned or not -- only way I can think
 * of to do this reasonably efficiently is to have every Action be a BitSet as
 * big as State.possible).
 *
 * @author travis
 */
public interface ActionType {

  /**
   * The int that will be used in Action.mode
   */
  public int getIndex();

  /**
   * Name of this action type.
   */
  public String getName();

  /**
   * Given action a (of this action type), and a current State's possible items
   * b, return the State resulting from applying the given action.
   */
  public State apply(Action a, State s);

  /**
   * Return a list of actions of this type that are possible according to the
   * given State.
   */
  public Iterable<Action> next(State s);

  /**
   * The increase in the loss function associated with taking action a in state
   * s when the correct answer is y. Should not return negative values
   * and 0 means no loss.
   */
  public double deltaLoss(State s, Action a, FNParse y);


  public static class CommitActionType implements ActionType {
    public static final Logger LOG = Logger.getLogger(CommitActionType.class);
    private final int index;
    private boolean forceLeftRightInference = false;

    public CommitActionType(int index) {
      this.index = index;
    }

    public void forceLeftRightInference() {
      forceLeftRightInference(true);
    }
    public void forceLeftRightInference(boolean doForce) {
      LOG.info("[COMMIT forceLeftRightInference] doForce=" + doForce);
      this.forceLeftRightInference = doForce;
    }

    @Override
    public int getIndex() {
      return index;
    }

    @Override
    public String getName() {
      return "COMMIT";
    }

    @Override
    public String toString() {
      return getName();
    }

    @Override
    public State apply(Action a, State s) {
      assert a.mode == index;

      // Copy the possible BitSet
      BitSet cur = s.getPossible();
      BitSet next = new BitSet(cur.cardinality());
      next.xor(cur);

      // Eliminate all other spans that this (t,k) could be assigned to.
      StateIndex si = s.getStateIndex();
      int n = si.sentenceSize();
      for (int i = 0; i < n; i++)
        for (int j = i + 1; j <= n; j++)
          next.set(si.index(a.t, a.k, i, j), false);

      // Update committed
      Span[][] c = s.copyOfCommitted();
      assert c[a.t][a.k] == null;
      c[a.t][a.k] = a.hasSpan() ? a.getSpan() : Span.nullSpan;

      return new State(s.getFrames(), si, next, c);
    }

    @Override
    public Iterable<Action> next(State st) {
      List<Action> actions = new ArrayList<>();
      int n = st.getSentence().size();
      int T = st.numFrameInstance();

      int tForce = -1, kForce = -1;
      if (forceLeftRightInference) {
        // Choose the first (t,k) that we haven't State.committed to.
        int[] tk = new int[2];
        if (st.findFirstNonCommitted(tk)) {
          tForce = tk[0];
          kForce = tk[1];
        }
      }

      // Build the commit actions.
      for (int t = 0; t < T; t++) {
        int K = st.getFrame(t).numRoles();
        for (int k = 0; k < K; k++) {

          if (forceLeftRightInference
              && (tForce >= 0 && kForce >= 0)
              && (t != tForce || k != kForce)) {
            continue;
          }

          Span a = st.committed(t, k);
          if (a != null) continue;
          // Consider all possible spans
          boolean somePossible = false;
          for (int i = 0; i < n; i++) {
            for (int j = i + 1; j <= n; j++) {
              if (st.possible(t, k, i, j)) {
                actions.add(new Action(t, k, index, Span.getSpan(i, j)));
                somePossible = true;
              }
            }
          }
          // A secondary role of this method is to clean up after PRUNE,
          // which man prune away all possibilities for a (t,k) without
          // realizing it, as it only checks whether the items it is pruning
          // are possible rather than the items it is *not pruning* (for a given t,k)
          if (!somePossible) {
            if (RerankerTrainer.PRUNE_DEBUG)
              LOG.info("COMMIT.next cleaned up a (t,k) from PRUNE");
            st.noPossibleItems(t, k);
          }
        }
      }
      return actions;
    }

    @Override
    public double deltaLoss(State s, Action a, FNParse y) {
      if (y == null)
        throw new IllegalArgumentException("you need a label!");
      final double costFP = 1d;
      final double costFN = Reranker.COST_FN;
      Span hyp = a.getSpanSafe();
      Span gold = y.getFrameInstance(a.t).getArgument(a.k);
      if (hyp == Span.nullSpan && gold == Span.nullSpan) {
        return 0;
      } else if (hyp != Span.nullSpan && gold == Span.nullSpan) {
        return costFP;
      } else if (hyp == Span.nullSpan && gold != Span.nullSpan) {
        return costFN;
      } else if (hyp != Span.nullSpan && gold != Span.nullSpan) {
        return hyp == gold ? 0 : costFP + costFN;
      } else {
        throw new RuntimeException("wat");
      }
    }
  };

  /**
   * TODO Remove cruft below and clarify!
   * 
   * PRUNE.contained: prunes all COMMIT actions that are *not contained within a given span*
   * PRUNE.crossing: prunes all COMMIT actions that *cross a given span*
   * PRUNE.POS_at_pos: (TODO) prunes all COMMIT actions that *have a given POS at a given position*
   * 
   * 
   * 
   * The idea of this action is to decide to give up on a given (t,k).
   *
   * WAIT: If I implement PRUNE instead, I'll get this for free... but more on
   * that after I write down exactly what I was thinking.
   *
   * Actions of this type will be built off of other COMMIT actions.
   * StopAdjoints {
   *    forwards = this.tau(t,k) - max_{a \in COMMIT(t,k)} a.forwards
   *    backwards(x = dScore/dForwards) {
   *      // increase tau if x is positive (on the good side of an update)
   *      // and decrease it otherwise.
   *      // do not mess with other actions' params.
   *      // well... if we did share params we would want to backprop to them...
   *      this.tau(t,k).backwards(x)
   *    }
   *    // initialize this.tau to return scores > 1
   * }
   *
   * If this action is chosen, it will reduce the number of COMMIT actions
   * available next time by a factor of K/TK.
   * Normally, if there are O(TK) actions to score, and trajectories are O(TK)
   * long, then the complexity is O(F*(TK)^2),
   * where F = features = time to compute cached static + dynamic features
   * If tau is large (this action is common) and it occurs at the beginning of
   * parsing, then we could hope to knock down the branching factor quickly
   * (it's not obvious what the complexity decrease is...)
   * 
   * Is the easy way to generalize this to arbitrary prunes just to perform the
   * max over COMMIT actions that would be pruned?! :)
   * Generate these actions sparingly (e.g. +/- 0,1,2 targets, 10,15,20 words,
   * or if syntax is available +/- some tree dist measure), featurize them based
   * on t, k, and how they were made, and let the hard work still be done in
   * COMMIT's features.
   * 
   * TODO Can COMMIT_AND_PRUNE, or at least the prune half of it, but subsumed
   * by this method? Sure, just have an action for PRUNE where the span is one
   * you just committed to.
   *
   * NOTE: You can leave off a k or both (t,k) to have these pruning actions
   * apply to more things!
   *
   * One problem with this is that we need some way for this ActionType to know
   * about the other COMMIT Actions that have been generated so far. This can be
   * orchestrated in a special implementation of TransitionFunction!
   * 
   * HOLY SHMOLY, I just realized I now have two action types: COMMIT and PRUNE,
   * and they're DUALS of each other! (in the sense that scores of PRUNEs have
   * a term of -max_i{score(COMMIT_i)}.
   */
  public static class PruneActionType implements ActionType {
    public static final Logger LOG = Logger.getLogger(PruneActionType.class);
    private final int index;
    private boolean forceLeftRightInference = false;

    public PruneActionType(int index) {
      this.index = index;
    }

    @Override
    public int getIndex() {
      return index;
    }

    public void forceLeftRightInference() {
      forceLeftRightInference(true);
    }
    public void forceLeftRightInference(boolean doForce) {
      LOG.info("[PRUNE forceLeftRightInference] doForce=" + doForce);
      this.forceLeftRightInference = doForce;
    }

    @Override
    public String getName() {
      return "PRUNE";
    }

    @Override
    public State apply(Action a, State s) {
      assert a.mode == getIndex();

      // Copy the possible BitSet
      BitSet cur = s.getPossible();
      BitSet next = new BitSet(cur.cardinality());
      next.xor(cur);

      // If we need to mutate this, we will do so in the following loops
      // (by asking for a copy). If it makes it through these loops, then the
      // same array can be re-used.
      Span[][] c = null;

      // Update possible
      StateIndex si = s.getStateIndex();
      int n = si.sentenceSize();
      int T = s.numFrameInstance();
      for (int t = 0; t < T; t++) {
        FrameInstance fi = s.getFrameInstance(t);
        int K = fi.getFrame().numRoles();
        for (int k = 0; k < K; k++) {
          // Check if this action lead to no more options for (t,k)
          boolean prunedEverything = true;
          for (int i = 0; i < n; i++) {
            for (int j = i + 1; j <= n; j++) {
              if (!s.possible(t, k, i, j))
                continue;
              else if (isPrunedBy(t, k, Span.getSpan(i, j), a))
                next.set(si.index(a.t, a.k, i, j), false);
              else
                prunedEverything = false;
            }
          }
          if (prunedEverything && s.committed(t, k) == null) {
            if (c == null)
              c = s.copyOfCommitted();
            c[t][k] = Span.nullSpan;
          }
        }
      }

      // Update committed
      // no-op (NOTE no copy)
//      Span[][] c = s.getCommitted();
      if (c == null)    // no-mutate => no-copy
        c = s.getCommitted();

      return new State(s.getFrames(), si, next, c);
    }

    @Override
    public Iterable<Action> next(State s) {
      throw new RuntimeException("should only be called from "
          + "TransitionFunction.Tricky and should use the other method instead.");
    }

    // TODO I'm beginning to think that I want to be handed the COMMIT Adjoints
    // in an indexed data structure where I can just ask for 0 or 1 Adjoints
    // given (t,k,i,j)
    // This probably isn't a problem because I can force ActionType.COMMIT.next
    // to generate this data structure, which is passed to TransitionFunction.Tricky
    // then here.
    public List<PruneAdjoints> next(State s,
        ActionSpanIndex<Adjoints.HasSpan> commitAdjoints,
        Params.PruneThreshold tauParams,
        boolean onlySimplePrunes) {

      List<PruneAdjoints> prunes = new ArrayList<>();

      int tForce = -1, kForce = -1;
      if (forceLeftRightInference) {
        // Choose the first (t,k) that we haven't State.committed to.
        int[] tk = new int[2];
        if (s.findFirstNonCommitted(tk)) {
          tForce = tk[0];
          kForce = tk[1];
        }
      }


      // Constraints:

      // 1) every PRUNE action must eliminate at least 1 possible item
      //    this can be checked here.

      // 2) every PRUNE must also have the Adjoints of a COMMIT action
      //    the COMMIT action must be possible(t,k,i,j) (and implicitly !committed[t][k])

      // 3) if !possible(t,k,i,j) \forall i,j => committed must be updated with i,j
      //    possible is only ever updated at application time
      //    if a COMMIT action is taken, clear how to enforce this
      //    if a PRUNE action is taken, then... we must check
      //      \exists i,j s.t. possible(t,k,i,j) && !(i,j).prunedBy(this)
      //      we can do this check at PRUNE construction or application time
      //      if @CONSTRUCTION: cache this info so at application we can know if we should flip committed
      //      if @APPLICATOIN: loop at application to see if committed needs to be updated


      // Think of a PRUNE action as a set of COMMIT actions.
      // Let S^c be the complement of S (assume PRUNE pertains to one t,k)
      // 1 says that we must loop over the set PRUNE, checking possible
      // 2 says the same thing
      // 3 says that we need to check PRUNE^c when deciding to update State.commmitted or not
      // I'm worried about looping over PRUNE^c
      
      // NOTE: If you only used PRUNE(t,k) (i.e. all i,j for a given t,k)
      // then you never need to loop over PRUNE^c, because its emptySet!
      
      // What happens if you just fail to update committed[t][k]?
      // i.e. [committed[t][k] == null] => ![\exists i,j s.t. possible(t,k,i,j)]
      // but not bidirectional/iff.
      // COMMIT.next will try to loop over everything, checking possible!
      // AHA, we can just make COMMIT.next update committed!
      // AND COMMIT.next will only run if we choose that action that lead to the partial update (of possible but not committed!)
      
      
      // AH! We can use State.possible to determine which pruning actions
      // should be introduced.
      


      // 1) crossing span(s)
      // 1a) (i,j) = the span of the last COMMIT action
      // 1b) every span (i,j) s.t. (i,j) is the span of a previous COMMIT action
      // 1c) every span (i,j) s.t. i is the start t and j is the end of (t+d) where d >= 1
      // 1d) every span (i,j) s.t. i is the start of a span filling (t,k) and j is the end of t (and the reverse for right-args)
      // 1e) every span (i,j) s.t. i is the start of an arg and j is the end of an arg
      


      // 2) contained
      int n = s.getSentence().size();
      int T = s.numFrameInstance();
      for (int t = 0; t < T; t++) {
        FrameInstance fi = s.getFrameInstance(t);
        Frame f = fi.getFrame();
        Span target = fi.getTarget();
        Span leftOfTarget = target.start > 0 ? Span.getSpan(0, target.start) : null;
        Span rightOfTarget = target.end < n ? Span.getSpan(target.end, n) : null;
        int K = f.numRoles();
        for (int k = 0; k < K; k++) {

          if (forceLeftRightInference
              && (tForce >= 0 && kForce >= 0)
              && (t != tForce || k != kForce)) {
            continue;
          }

          // 2a) all words (i.e. COMMIT to nullSpan for this t,k)
          // (nothing is contained within nullSpan)
          tryAddPruneNotContainedIt(
              t, k, Span.nullSpan, s, commitAdjoints, "nullSpan", tauParams, onlySimplePrunes, prunes);

          if (onlySimplePrunes)
            continue;

          // 2b) left/right of target
          tryAddPruneNotContainedIt(
              t, k, leftOfTarget, s, commitAdjoints, "leftOfTarget", tauParams, onlySimplePrunes, prunes);
          tryAddPruneNotContainedIt(
              t, k, rightOfTarget, s, commitAdjoints, "rightOfTarget", tauParams, onlySimplePrunes, prunes);

          // 2c) inside boundary set by K targets in both directions
          // TODO

          // 2d) R words from target
          for (int dist : Arrays.asList(8, 16)) {
            if (target.start - dist < 0 || target.end + dist > n)
              continue;
            Span window = Span.getSpan(target.start - dist, target.end + dist);
            tryAddPruneNotContainedIt(
                t, k, window, s, commitAdjoints, "window" + dist, tauParams, onlySimplePrunes, prunes);
          }
        }
        
        
        // 3) TODO near by POS
        // 3a) starts with POS X
        // 3b) ends with POS X
        // 3c) left POS is X
        // 3d) right POS is X
      }

      return prunes;
    }

    /**
     * Checks that there is at least one item that would be prune by only allowing
     * (t,k,arg) s.t. arg in container, and if there is, adds this PRUNE action
     * to addTo.
     *
     * TODO take a (t,k,i,j)-indexed COMMIT Action data structure so that
     * we can complete the PruneAdjoints.
     *
     * @param container may be null, and nothing will be added to addTo.
     * @param providenceFeature is how this span was chosen, e.g. "leftOfTarget"
     */
    private void tryAddPruneNotContainedIt(
        int t, int k, Span container,
        State s,
        ActionSpanIndex<Adjoints.HasSpan> commitActions,
        String providenceFeature,
        Params.PruneThreshold tauParams,
        boolean onlySimplePrunes,
        Collection<PruneAdjoints> addTo) {

      if (container == null)
        return;

      if (s.committed(t, k) != null)
        return;

      FNTagging frames = s.getFrames();

      // (t,k)
      if (RerankerTrainer.PRUNE_DEBUG) {
        LOG.info("[tryAddPruneNotContainedIn] t=" + t + " k=" + k
            + " container=" + container.shortString() + " providence=" + providenceFeature);
      }
      List<Adjoints.HasSpan> tkCommActions =
          commitActions.notContainedIn(t, k, container, new ArrayList<>());
      if (tkCommActions == null || tkCommActions.size() > 0) {
        PruneAdjoints tkPrune = pruneNotContainedIn(t, k, container);
        Adjoints tkTau = tauParams.score(frames, tkPrune, providenceFeature);
        tkPrune.turnIntoAdjoints(tkTau, tkCommActions);
        addTo.add(tkPrune);
      }

      if (onlySimplePrunes)
        return;

      // (t,*)
      if (k == 0) {   // don't double-create this action
        if (RerankerTrainer.PRUNE_DEBUG) {
          LOG.info("[tryAddPruneNotContainedIn] t=" + t + " k=*"
              + " container=" + container.shortString() + " providence=" + providenceFeature);
        }
        List<Adjoints.HasSpan> tCommActions =
            commitActions.notContainedIn(t, container, new ArrayList<>());
        if (tCommActions == null || tCommActions.size() > 0) {
          PruneAdjoints tPrune = pruneNotContainedIn(t, container);
          Adjoints tTau = tauParams.score(frames, tPrune, providenceFeature);
          tPrune.turnIntoAdjoints(tTau, tCommActions);
          addTo.add(tPrune);
        }
      }

      // (*,*)
      if (t == 0 && k == 0) {   // don't double-create this action
        if (RerankerTrainer.PRUNE_DEBUG) {
          LOG.info("[tryAddPruneNotContainedIn] t=* k=*"
              + " container=" + container.shortString() + " providence=" + providenceFeature);
        }
        List<Adjoints.HasSpan> allCommActions =
            commitActions.notContainedIn(container, new ArrayList<>());
        if (allCommActions == null || allCommActions.size() > 0) {
          PruneAdjoints prune = pruneNotContainedIn(container);
          Adjoints tau = tauParams.score(frames, prune, providenceFeature);
          prune.turnIntoAdjoints(tau, allCommActions);
          addTo.add(prune);
        }
      }
    }

    @Override
    public double deltaLoss(State s, Action a, FNParse y) {
      assert a.mode == getIndex();
      // Count the number of gold items that would be pruned by this action
      // (note: they must not have already been pruned).
      int pruned = 0;
      int T = y.numFrameInstances();
      for (int t = 0; t < T; t++) {
        FrameInstance fi = y.getFrameInstance(t);
        int K = fi.getFrame().numRoles();
        for (int k = 0; k < K; k++) {
          Span arg = fi.getArgument(k);
          if (arg == Span.nullSpan) continue;
          if (s.possible(t, k, arg) && isPrunedBy(t, k, arg, a))
            pruned++;
        }
      }
      return pruned * Reranker.COST_FN;
    }

    /**
     * @return true is pruneAction prunes/invalidates commitAction
     */
    public boolean isPrunedBy(Action commitAction, PruneAdjoints pruneAction) {
      assert commitAction.mode == COMMIT.getIndex();
      assert commitAction.hasSpan();
      Span arg = commitAction.getSpan();
      return isPrunedBy(commitAction.t, commitAction.k, arg, pruneAction);
    }

    public String describe(PruneAdjoints a) {
      boolean crossing = a.end < 0;
      String tk;
      if (a.t < 0 && a.k < 0)
        tk = "t=* k=*";
      else if (a.t >= 0 && a.k < 0)
        tk = "t=" + a.t + " k=*";
      else if (a.t >= 0 && a.k >= 0)
        tk = "t=" + a.t + " k=" + a.k;
      else
        throw new RuntimeException();
      if (crossing) {
        Span s = Span.getSpan(a.start, -a.end);
        return "(PRUNE.crossing " + tk + " " + s.shortString() + ")";
      } else {
        // contained
        Span s = Span.getSpan(a.start, a.end);
        return "(PRUNE.notContainedIn " + tk + " " + s.shortString() + ")";
      }
    }

    /**
     * @return true if (t,k,arg) is pruned by a.
     */
    public boolean isPrunedBy(int t, int k, Span arg, Action a) {
      // OPTIONS:
      // 1) prune all actions that are not CONTAINED in [start,end)
      // 2) prune all actions that OVERLAP with [start,end)

      // ENCODING:
      // I can hide 2 bits or 4 options in the sign of start and end
      // (0,0) is nullSpan, but otherwise I should be able to tell what the
      // sign should be given some possibly sign-flipped start and ends.
      // => lets say that if end < 0, then use OVERLAP instead of CONTAINED

      boolean crossing = a.end < 0;
      if (crossing) {
        assert a.start < -a.end;
        Span s = Span.getSpan(a.start, -a.end);
        return (a.t < 0 || a.t == t)
            && (a.k < 0 || a.k == k)
            && arg.crosses(s);
      } else {
        // contained
        boolean contained = a.start <= arg.start && arg.end <= a.end;
        return (a.t < 0 || a.t == t)
            && (a.k < 0 || a.k == k)
            && !contained;
      }
    }

    /** Applies to all (t,k) */
    public PruneAdjoints pruneCrossing(Span s) {
      return pruneCrossing(-1, -1, s);
    }
    /** Applies to all k for this t */
    public PruneAdjoints pruneCrossing(int t, Span s) {
      return pruneCrossing(t, -1, s);
    }
    /**
     * Returns an Action that prunes all items belonging to (t,k) that have a
     * span that overlap with the given span.
     */
    public PruneAdjoints pruneCrossing(int t, int k, Span s) {
      assert s.start < s.end && s.start >= 0;
      return new PruneAdjoints(t, k, getIndex(), s.start, -s.end);
    }


    /** Applies to all (t,k) */
    public PruneAdjoints pruneNotContainedIn(Span s) {
      return pruneNotContainedIn(-1, -1, s);
    }
    /** Applies to all k for this t */
    public PruneAdjoints pruneNotContainedIn(int t, Span s) {
      return pruneNotContainedIn(t, -1, s);
    }
    /**
     * Returns an action that prunes all items belonging to (t,k) that have a
     * span that does not fit inside the given span
     * (i.e. subset relation on spans as token sets).
     */
    public PruneAdjoints pruneNotContainedIn(int t, int k, Span s) {
      assert s.end >= 0;
      return new PruneAdjoints(t, k, getIndex(), s.start, s.end);
    }
  };

  public static final CommitActionType COMMIT = new CommitActionType(0);
  public static final PruneActionType PRUNE = new PruneActionType(1);
  public static final ActionType[] ACTION_TYPES = new ActionType[] {
    COMMIT,
    PRUNE,
  };
}
