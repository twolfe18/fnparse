package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.util.AveragedWeights;
import edu.jhu.util.Alphabet;

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
   * Given action a (of this action type), and a current State's possible items
   * b, return the BitSet that would have lead to s under a.
   *
   * You can assume this action came from next().
   *
   * @deprecated only need forwards now
   */
  public State unapply(Action a, State s);

  /**
   * Return a list of actions of this type that are possible according to the
   * given State.
   *
   * If y != null, then only return actions that are compatible with eventually
   * reaching a State corresponding to the parse y.
   *
   * TODO Do we actually ever need to intersect with actions that are consistent
   * with y? I'll leave it for now, but probably should be removed.
   */
  public Iterable<Action> next(State s, FNParse y);

  /**
   * Return a list of actions of this type that would lead to the given State.
   *
   * @deprecated only need forwards now
   */
  public Iterable<Action> prev(State s);

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
    public State unapply(Action a, State s) {
      // Copy the possible BitSet
      BitSet cur = s.getPossible();
      BitSet prev = new BitSet(cur.cardinality());
      prev.xor(cur);

      // Update possible
      StateIndex si = s.getStateIndex();
      int n = si.sentenceSize();
      for (int i = 0; i < n; i++)
        for (int j = i + 1; j <= n; j++)
          prev.set(si.index(a.t, a.k, i, j), true);

      // Update committed
      Span[][] c = s.copyOfCommitted();
      Span afterUpdateS = c[a.t][a.k];
      assert (afterUpdateS == Span.nullSpan && !a.hasSpan())
          || (afterUpdateS == a.getSpan());
      c[a.t][a.k] = null;

      return new State(s.getFrames(), si, prev, c);
    }

    @Override
    public Iterable<Action> next(State st, FNParse y) {
      List<Action> actions = new ArrayList<>();
      int T = st.numFrameInstance();
      for (int t = 0; t < T; t++) {
        int K = st.getFrame(t).numRoles();
        for (int k = 0; k < K; k++) {
          Span a = st.committed(t, k);
          if (a != null) continue;
          if (y == null) {
            // Consider all possible actions
            for (Span arg : st.naiveAllowableSpans(t, k)) {
              if (st.possible(t, k, arg))
                actions.add(new Action(t, k, getIndex(), arg));
            }
          } else {
            // Only consider actions that will lead to y (there is only one)
            assert false : "this code path is deprecated!";
            Span yArg = y.getFrameInstance(t).getArgument(k);
            assert st.possible(t, k, yArg);
            actions.add(new Action(t, k, getIndex(), yArg));
          }
        }
      }
      return actions;
    }

    /**
     * @deprecated
     */
    @Override
    public Iterable<Action> prev(State st) {
      List<Action> actions = new ArrayList<>();
      int T = st.numFrameInstance();
      for (int t = 0; t < T; t++) {
        int K = st.getFrame(t).numRoles();
        for (int k = 0; k < K; k++) {
          Span a = st.committed(t, k);
          if (a == null)
            continue;
          // Make an action that would have lead to this (t,k) being committed
          actions.add(new Action(t, k, getIndex(), a));
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

  public static final ActionType COMMIT_AND_PRUNE = new ActionType() {
    @Override
    public int getIndex() {
      return 1;
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

    /**
     * @deprecated
     */
    @Override
    public State unapply(Action a, State s) {
      throw new RuntimeException("This is an impossible task, and a design flaw."
          + " This ActionType should never propose previous actions.");
    }

    @Override
    public Iterable<Action> next(State st, FNParse y) {
      Iterable<Action> actions = COMMIT.next(st, y);
      actions = prune(actions, st);
      return actions;
    }

    /**
     * @deprecated
     */
    @Override
    public Iterable<Action> prev(State st) {
      // TODO figure this out.
      //return prune(COMMIT.prev(st), st);
      return Collections.emptyList();
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
      return 2;
    }

    @Override
    public String getName() {
      return "COMMIT_AND_PRUNE_X";
    }

    @Override
    public State apply(Action action, State state) {
      PruneXAction a = (PruneXAction) action;
      Span s = a.getSpan();

      // Prune any item that does not fall in s
      throw new RuntimeException("implement me");
    }

    @Override
    public State unapply(Action a, State s) {
      throw new RuntimeException("no longer supported");
    }

    @Override
    public Iterable<Action> next(State s, FNParse y) {
      throw new RuntimeException("implement me");
    }

    @Override
    public Iterable<Action> prev(State s) {
      throw new RuntimeException("no longer supported");
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
    COMMIT_AND_PRUNE,
  };
}
