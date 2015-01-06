package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Span;

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
   */
  public State unapply(Action a, State s);

  /**
   * Return a list of actions of this type that are possible according to the
   * given State.
   * 
   * If y != null, then only return actions that are compatible with eventually
   * reaching a State corresponding to the parse y.
   */
  public Iterable<Action> next(State s, FNParse y);

  /**
   * Return a list of actions of this type that would lead to the given State.
   */
  public Iterable<Action> prev(State s);

  /**
   * The increase in the loss function associated with taking action a in state
   * s when the correct answer is y.
   */
  public double deltaLoss(State s, Action a, FNParse y);


  /* Don't use this, it may introduce non-determinism across runs
  public static class ActionTypeIndex {
    private static Alphabet<ActionType> alph = new Alphabet<>();
    public static int getIndex(ActionType newActionType) {
      return alph.lookupIndex(newActionType, true);
    }
    public ActionType getActionType(int i) {
      return alph.lookupObject(i);
    }
  } */

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
            for (Span arg : st.naiveAllowableSpans(t, k))
              actions.add(new Action(t, k, getIndex(), arg));
          } else {
            // Only consider actions that will lead to y (there is only one)
            Span yArg = y.getFrameInstance(t).getArgument(k);
            actions.add(new Action(t, k, getIndex(), yArg));
          }
        }
      }
      return actions;
    }

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
      // Check for false positives (proposing a bad argument)
      final double costFP = 1d;
      if (a.hasSpan()) {
        Span hyp = a.getSpan();
        Span gold = y.getFrameInstance(a.t).getArgument(a.k);
        if (hyp != gold)
          return costFP;
      }
      return 0d;
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
    public State unapply(Action a, State s) {
      throw new RuntimeException("implement me");
    }

    @Override
    public Iterable<Action> next(State st, FNParse y) {
      return prune(COMMIT.next(st, y), st);
    }

    @Override
    public Iterable<Action> prev(State st) {
      return prune(COMMIT.prev(st), st);
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
      // Cost of false positives
      double cost = COMMIT.deltaLoss(s, a, y);
      // Check for false negatives (pruning a gold argument)
      final double costFN = 1d;
      if (!a.hasSpan()) {
        assert a.start == Span.nullSpan.start && a.end == Span.nullSpan.end;
        Span gold = y.getFrameInstance(a.t).getArgument(a.k);
        if (gold != Span.nullSpan)
          cost += costFN;
      }
      return cost;
    }
  };

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
