package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.util.AveragedWeights;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleVector;
import edu.jhu.util.Alphabet;

/**
 * A class representing a type of Action which provides the information needed
 * to plug into ForwardSearch/learning.
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
   * b, return the BitSet resulting from applying the given action.
   *
   * Remember, this must be monotonic in State.possible, so this could be
   * represented as a bitwise &=
   */
  public State apply(Action a, State s);

  /**
   * Return a list of actions of this type that are possible according to the
   * given State.
   */
  public Iterable<Action> next(State s);

  /**
   * The increase in the loss function associated with taking action a in state
   * s when the correct answer is y.
   */
  public double deltaLoss(State s, Action a, FNParse y);


  public static final ActionType COMMIT = new ActionType() {
    @Override
    public int getIndex() {
      return 0;
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
      // Copy the possible BitSet
      BitSet cur = s.getPossible();
      BitSet next = new BitSet(cur.cardinality());
      next.xor(cur);

      // Update possible
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
      int T = st.numFrameInstance();
      for (int t = 0; t < T; t++) {
        int K = st.getFrame(t).numRoles();
        for (int k = 0; k < K; k++) {
          Span a = st.committed(t, k);
          if (a != null) continue;
          // Consider all possible actions
          for (Span arg : st.naiveAllowableSpans(t, k)) {
            if (st.possible(t, k, arg))
              actions.add(new Action(t, k, getIndex(), arg));
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
      final double costFN = 1d;
      Span hyp = a.getSpanSafe();
      Span gold = y.getFrameInstance(a.t).getArgument(a.k);
      if (hyp != gold) {
        if (gold == Span.nullSpan)
          return costFP;
        else
          return costFN;
      } else {
        return 0d;
      }
    }
  };

  /**
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
  public static final Prune PRUNE = new Prune();
  public static class Prune implements ActionType {

    @Override
    public int getIndex() {
      return 1;
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

      // Update possible
      StateIndex si = s.getStateIndex();
      int n = si.sentenceSize();
      int T = s.numFrameInstance();
      for (int t = 0; t < T; t++) {
        FrameInstance fi = s.getFrameInstance(t);
        int K = fi.getFrame().numRoles();
        for (int k = 0; k < K; k++)
          for (int i = 0; i < n; i++)
            for (int j = i + 1; j <= n; j++)
              if (isPrunedBy(t, k, Span.getSpan(i, j), a))
                next.set(si.index(a.t, a.k, i, j), false);
      }

      // Update committed
      // no-op (NOTE no copy)
      Span[][] c = s.getCommitted();

      return new State(s.getFrames(), si, next, c);
    }

    @Override
    public Iterable<Action> next(State s) {
      throw new RuntimeException("should only be called from "
          + "TransitionFunction.Tricky and should use the other method instead.");
    }

    public List<PruneAdjoints> next(State s, List<Adjoints> commitActions) {

      List<PruneAdjoints> prunes = new ArrayList<>();

      // 1) crossing
      // 1a) any committed action that we haven't chosen a crossing prune action for
      // TODO

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

          // TODO upon construction, here, pass along an indicator
          // feature/string/int saying how we made this pruning rule

          // 2a) left/right of target
          if (leftOfTarget != null)
            prunes.add(pruneNotContainedIn(t, k, leftOfTarget));
          if (rightOfTarget != null)
            prunes.add(pruneNotContainedIn(t, k, rightOfTarget));
          if (k == 0) {
            if (leftOfTarget != null)
              prunes.add(pruneNotContainedIn(t, leftOfTarget));
            if (rightOfTarget != null)
              prunes.add(pruneNotContainedIn(t, rightOfTarget));
          }

          // 2b) inside boundary set by K targets in both directions
          // TODO

          // 2c) R words from target
          for (int dist : Arrays.asList(8, 16)) {
            if (target.start - dist < 0 || target.end > n)
              continue;
            Span window = Span.getSpan(target.start - dist, target.end + dist);
            prunes.add(pruneNotContainedIn(t, k, window));
            if (k == 0)
              prunes.add(pruneNotContainedIn(t, window));
          }
        }
      }

      // Give PRUNE actions the Adjoints/scores/features of the COMMIT actions
      // that they prohibit.
      // TODO is there an efficient way to do this? I think a real answer for
      // this will have to wait, just do n^2 loop for now.
      for (PruneAdjoints p : prunes) {
        List<Adjoints> commitsThatWillBePruned = new ArrayList<>();
        for (Adjoints comm : commitActions)
          if (isPrunedBy(comm.getAction(), p))
            commitsThatWillBePruned.add(comm);
        Frame f = s.getFrame(p.t);
        Adjoints tau = getThresholdFeatures(f, p.k);
        p.turnIntoAdjoints(tau, commitsThatWillBePruned);
      }

      return prunes;
    }

    // TODO This ActionType currently has params/weights, and these should get
    // moved out to something non-global.
    private FrameRolePacking frPacking = new FrameRolePacking();
    private IntDoubleVector tauWeights = new IntDoubleDenseVector(frPacking.size() + 1);
    private Adjoints getThresholdFeatures(Frame frame, int k) {
      FeatureVector fv = new FeatureVector();
      fv.add(frPacking.index(frame, k), 1d);
      fv.add(frPacking.index(frame), 1d);
      return new Adjoints.Vector(null, tauWeights, fv, 0d, 1d);
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
      return pruned;
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

      assert a.end != 0;
      boolean crossing = a.end < 0;
      if (crossing) {
        assert a.start < -a.end;
        Span s = Span.getSpan(a.start, -a.end);
        return (a.t < 0 || a.t == t)
            && (a.k < 0 || a.k == k)
            && arg.crosses(s);
      } else {
        // contained
        assert a.start < a.end;
        return (a.t < 0 || a.t == t)
            && (a.k < 0 || a.k == k)
            && a.start <= arg.start && arg.end <= a.end;
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
      assert s.start < s.end && s.start >= 0;
      return new PruneAdjoints(t, k, getIndex(), s.start, s.end);
    }
  };

  /**
   * Commits a (t,k,span), just like COMMIT, and then makes (t',k',span')
   * impossible if span and span' overlap.
   *
   * @deprecated Use a COMMIT action and then a PRUNE(crossing) instead.
   */
  public static final ActionType COMMIT_AND_PRUNE = new ActionType() {

    @Override
    public int getIndex() {
      return 2;
    }

    @Override
    public String getName() {
      return "COMMIT_AND_PRUNE";
    }

    @Override
    public String toString() {
      return getName();
    }

    @Override
    public State apply(Action a, State s) {
      assert a.mode == getIndex();
      assert a.hasSpan() : "use COMMIT for nullSpan";

      // COMMIT does a lot of the work for us
      State next = COMMIT.apply(a, s);
      StateIndex si = next.getStateIndex();
      BitSet nextPoss = next.getPossible();

      // Rule out other spans
      assert a.start >= 0;
      assert a.width() > 1;
      int n = si.sentenceSize();
      for (int t = 0; t < si.numFrameInstances(); t++) {
        for (int k = 0; k < si.numRoles(t); k++) {

          if (t == a.t && k == a.k)
            continue;

          // Spans that end in this span.
          for (int i = 0; i < a.start; i++) {
            for (int j = a.start; j < a.end; j++) {
              nextPoss.set(si.index(t, k, i, j), false);
            }
          }

          // Spans that start in this span.
          for (int i = a.start + 1; i < a.end; i++) {
            for (int j = a.end; j <= n; j++) {
              nextPoss.set(si.index(t, k, i, j), false);
            }
          }
        }
      }
      return next;
    }

    @Override
    public Iterable<Action> next(State st) {
      Iterable<Action> actions = COMMIT.next(st);
      actions = prune(actions, st);
      return actions;
    }

    private Iterable<Action> prune(Iterable<Action> itr, State st) {
      itr = pruneNullSpan(itr);
      itr = pruneWidth1Actions(itr);
      List<Span> committedTo = st.getCommittedSpans(new ArrayList<>());
      itr = prunesActionsThatPrune(committedTo, itr);
      itr = changeModeTo(getIndex(), itr);
      return itr;
    }

    @Override
    public double deltaLoss(State s, Action a, FNParse y) {
      double cost = COMMIT.deltaLoss(s, a, y);
      // COMMIT checks for false negatives for this (t,k), here we need to check
      // for additional false negatives for all roles that might have a span
      // that overlaps with this action.
      assert a.hasSpan();
      Span arg = a.getSpan();
      final int T = s.numFrameInstance();
      for (int t = 0; t < T; t++) {
        FrameInstance fi = s.getFrameInstance(t);
        final int K = fi.getFrame().numRoles();
        for (int k = 0; k < K; k++) {
          Span c = s.committed(t, k);
          if (c != null && c.overlaps(arg) && c != arg)
            cost += Reranker.COST_FN;
        }
      }
      return cost;
    }
  };


  /**
   * @deprecated This lead to the implementation of PRUNE, but the notes are
   * still useful.
   *
   * The idea of this ActionType is that a bunch of instances will be created
   * given each t. Each instance will have 1 (or a small number of) features
   * which basically say "how was this instance created". For example, given
   * t = {frame:foo, target:3-4}
   * one of the actions instantiated by this type would have the feature
   * "prune-spans-right-of-next-target"
   * or "prune-spans-right-of-next-DT"
   * or "prune-spans-more-than-10-tokens-right"
   *    (last one might have additional BoW features for words in those 10 tokens)
   *
   * These "explanations"/features can/should be conjoined with frame/frameRole/role.
   *
   * We may instantiate many of these Actions, but they should be cheap to evaluate
   * and should save a lot of time any time one of them is chosen, by ruling out
   * a lot of potential spans.
   *
   * TODO where do these features live (seems like the code that generates these
   * should be the same code as the ones who featurize it).
   *
   * How do we capture the semantics of the prune?
   * I think I had proposed giving an "envelope", defined by these descriptions
   * above, where the code that applies the prune only has to know to prune spans
   * that don't fall within that envelope (and the secret sauce is in the generation
   * and featurization of these envelopes).
   *
   * To play devil's advocate, why am I doing this now and not a long time ago?
   * Many of these pruning actions probably work in a static context (i.e. they
   * don't need to know about arguments committed to -- for that matter they
   * could be implemented as features right now). There is some difference
   * between their implementation as *features* vs *pruning rules*, but I'm not
   * sold that this is a huge thing... maybe it is.
   * Actually, I think I do have features like this, but they apply for every
   * (t,k), as opposed to every t, and they cannot "make a unilateral decision"
   * to prune, they have to win out over other features that might disagree.
   * 
   * Under the "envelope" implementation, I can use start and end in Action to
   * store this. Still need to figure out how to featurize these. Normally, I
   * need to wait until Params.score comes around to extract the features.
   * - I could have a subclass of Action that stores the features...
   * Who is going to hold the alphabet and the weights?
   * I could have a class that implements both ActionType and Params...
   *
   * TODO I shouldn't even make this a COMMIT type action!
   * 
   * TODO I think I'm missing something pretty fundamental: I have no way for
   * scoring actions to know about the score of other actions that have already
   * been computed. For example, to fully support the actions of the previous
   * model, I would need an action that takes the max of spans over a given (t,k)
   * and commits to it. COMMIT_AND_PRUNE is close to this, but for example, there
   * is no way to say "only do a COMMIT_AND_PRUNE if you've scored all of the
   * actions that will be pruned". Lets come up with a simpler example that is
   * still not possible: ...
   * Wait, Action is pretty general. There is no reason I couldn't have an Action
   * that gets to look at more than one commit.
   *
   * I have tried to think out how you would design an Action that is an "argmax for a (t,k)",
   * and the problem comes with propagating the error of a bad choice back to the
   * params inside the argmax. If you got a argmax span wrong, you could push down
   * the features that lead to the span that won the argmax, but you have no
   * signal on what to push up (i.e. what arg should have won the argmax?)
   * 
   * Remember that the score returned by forwards() is just whether this action
   * should be take or not, but not (necessarily) related to the scores of the
   * spans being selected.
   *
   * This is one way to look at compositional actions (put everything into an
   * action). Another way of doing this is to put the capability to do this
   * inside the machine applying the actions.
   * Perhaps we could support two-stage actions. The first stage would be to choose
   * the (t,k) to do the argmax on, and the second stage would be to do the argmax.
   * The net result of this is a "rank 1 action" -- same as being scored currently,
   * but where the Learner needs to support separate updates for the first and
   * second stage. For the first stage, how do you know whether to update up or
   * down? I guess you could use deltaLoss on the final rank1 action it produced...
   *
   * Aside from two-stage stuff, is there a way for Actions to be self-aware?
   * I could just add ???
   */
  public static final ActionType COMMIT_AND_PRUNE_X = new ActionType() {

    @Override
    public int getIndex() {
      return 3;
    }

    @Override
    public String getName() {
      return "COMMIT_AND_PRUNE_X";
    }

    @Override
    public State apply(Action action, State state) {
//      PruneXAction a = (PruneXAction) action;
//      Span s = a.getSpan();

      // Prune any item that does not fall in s
      throw new RuntimeException("implement me");
    }

    @Override
    public Iterable<Action> next(State s) {
      throw new RuntimeException("implement me");
    }

    @Override
    public double deltaLoss(State s, Action a, FNParse y) {
      assert a.getActionType() == this;
      assert a.hasSpan();
      // consider gold args that are outside of a.getSpan()
      throw new RuntimeException("implement me");
    }
  };
  // TODO make this an inner class of the class that implements ActoinType and Params.State???
  // this will then inherit the Alphabet<String> and weights from there.
  /** @deprecated Just a mock-up */
  public static class PruneXAction extends Action implements Adjoints {
    private FeatureVector features;
    private Alphabet<String> featureNames;  // see above
    private AveragedWeights weights;        // see above
    public PruneXAction(int t, int k, int mode, Span s) {
      super(t, k, mode, s);
      features = new FeatureVector();
    }
    public void b(String... featurePieces) {
      // add to features
      throw new RuntimeException("implement me");
    }
    @Override
    public Action getAction() {
      return this;
    }
    @Override
    public double forwards() {
      // TODO compute this once so that weights don't change?
      return features.dot(weights.getWeights());
    }
    @Override
    public void backwards(double dScore_dForwards) {
      // update weights
      throw new RuntimeException("implement me");
    }
  }

  public static Iterable<Action> changeModeTo(int mode, Iterable<Action> change) {
    return Iterables.transform(change, new Function<Action, Action>() {
      private final int m = mode;
      @Override
      public Action apply(Action input) {
        input.mode = m;
        return input;
      }
    });
  }

  public static Iterable<Action> prunesActionsThatPrune(Collection<Span> args, Iterable<Action> prune) {
    if (args.contains(Span.nullSpan))
      throw new RuntimeException("this won't allow any prunes!");
    return Iterables.filter(prune, new Predicate<Action>() {
      @Override
      public boolean apply(Action input) {
        if (!input.hasSpan())
          return true;
        Span s1 = input.getSpan();
        for (Span s2 : args)
          if (s1.overlaps(s2) && !s1.equals(s2))
            return false;
        return true;
      }
    });
  }

  public static Iterable<Action> pruneNullSpan(Iterable<Action> input) {
    return Iterables.filter(input, new Predicate<Action>() {
      @Override
      public boolean apply(Action input) {
        return input.hasSpan();
      }
    });
  }

  public static Iterable<Action> pruneWidth1Actions(Iterable<Action> itr) {
    return Iterables.filter(itr, new Predicate<Action>() {
      @Override
      public boolean apply(Action input) {
        return input.width() != 1;
      }
    });
  }

  public static final ActionType[] ACTION_TYPES = new ActionType[] {
    COMMIT,
    PRUNE,
    COMMIT_AND_PRUNE,
    COMMIT_AND_PRUNE_X,
  };
}
