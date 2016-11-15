package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiFunction;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda.AgendaItem;
import edu.jhu.hlt.uberts.DecisionFunction.ByGroup.ByGroupMode;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.auto.Term;
import edu.jhu.prim.tuple.Pair;

/**
 * Given an (edge, score) just popped off the agenda, decide whether this edge
 * should be kept and put on the State, or pruned.
 *
 * TODO The way the interface is currently designed is for HARD constraints
 * (which have no parameters in them). Change this so that decide() returns
 * some Adjoints representing the justification (or parameters) which lead to
 * saying yes or no to this edge. This way you can implement SOFT constraints.
 *
 * @author travis
 */
public interface DecisionFunction {
  public static int DEBUG = 1;

  public static final DecisionFunction DEFAULT = new Constant(0);

  /**
   * Resets the state of this decision function, if there is any.
   */
  public void clear();

  /**
   * Return null if this decision function is not applicable (and the next
   * function in the cascade should be queried).
   */
  public Boolean decide(HypEdge e, Adjoints s);

  /**
   * The idea here is that you not only return a decision, but a way to change
   * the outcome next time (the Adjoints).
   *
   * If pred=true is returned and e is gold=false (a FP), then call
   * backwards(dErr_dForwards=+1) to "lower" the score of this pred=true decision
   * (on the pun that the decision function is score>0). Similarly, for FNs
   * backwards(dErr_dForwards=-1).
   */
  public Pair<Boolean, Adjoints> decide2(HypEdge e, Adjoints s);

  default public Pair<Boolean, Adjoints> decide2(AgendaItem ai) {
    return decide2(ai.edge, ai.score);
  }

  default public boolean decide(AgendaItem ai) {
    return decide(ai.edge, ai.score);
  }

  /**
   * A set of DecisionFunctions which may not disagree. A disagreement is when
   * two decision functions return non-null values which are not equal.
   * Imperatively, all decision functions are asked about every edge, so they
   * are free to track state related to how/when/with-what decide() is called.
   */
  public static class Unanimous implements DecisionFunction {

    /**
     * Accepts white-space-separated items which can be parsed by {@link
     * ByGroup#parse(String)}. Returns a {@link Unanimous} {@link DecisionFunction}
     */
    public static DecisionFunction.Unanimous parseMany(String description, Uberts u) {
      String[] rules = description.split("\\s+");
      DecisionFunction.Unanimous df = new DecisionFunction.Unanimous();
      for (int i = 0; i < rules.length; i++)
        df.add(ByGroup.parse(rules[i], u));
      return df;
    }

    private List<DecisionFunction> committee;
    public Unanimous() {
      this(Collections.emptyList());
    }
    public Unanimous(DecisionFunction df) {
      this(Arrays.asList(df));
    }
    public Unanimous(Collection<DecisionFunction> dfs) {
      this.committee = new ArrayList<>();
      this.committee.addAll(dfs);
    }
    public void add(DecisionFunction df) {
      this.committee.add(df);
    }
    @Override
    public void clear() {
      for (DecisionFunction df : committee)
        df.clear();
    }

    @Override
    public Boolean decide(HypEdge e, Adjoints s) {
//      if (DEBUG > 1 && e.getRelation().getName().equals("srl3"))
//        System.currentTimeMillis();
      Boolean d = null;
      DecisionFunction decider = null;
      for (DecisionFunction df : committee) {
        Boolean dfd = df.decide(e, s);
        if (dfd == null)
          continue;
        if (d != null && d != dfd) {
          throw new RuntimeException(decider + " said " + d + " but "
              + df + " says " + dfd);
        }
        d = dfd;
        decider = df;
      }
//      if (DEBUG > 0 && e.getRelation().getName().equals("argument4"))
//        Log.info("d=" + d + " decider=" + decider + " score>0=" + (s.forwards() > 0) + " " + s.forwards());
      return d;
    }

    @Override
    public Pair<Boolean, Adjoints> decide2(HypEdge e, Adjoints s) {
      // keep track of all deciders, make sure they agree
      // construct Adjoints to backprop to all of the deciders
      Boolean d = null;
      Adjoints da = null;
      for (DecisionFunction df : committee) {
        Pair<Boolean, Adjoints> dfd = df.decide2(e, s);
        if (dfd.get1() == null)
          continue;
        if (d != null && d != dfd.get1())
          throw new RuntimeException();
        d = dfd.get1();
        if (da == null)
          da = dfd.get2();
        else
          da = Adjoints.sum(da, dfd.get2());
      }
      return new Pair<>(d, da);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("(Unanimous");
      for (DecisionFunction df : committee) {
        sb.append(' ');
        sb.append(df);
      }
      sb.append(')');
      return sb.toString();
    }
  }

  public static class DispatchByRelation implements DecisionFunction {

    /**
     * Accepts white-space-separated items which can be parsed by {@link
     * ByGroup#parse(String)}. Returns a {@link Unanimous} {@link DecisionFunction}
     */
    public static DispatchByRelation parseMany(String description, Uberts u) {
      String[] rules = description.split("\\s+");
      DispatchByRelation df = new DispatchByRelation();
      for (int i = 0; i < rules.length; i++) {
        ByGroup bg = ByGroup.parse(rules[i], u);
        Relation rel = bg.relation;
        if (rel.getName().equals("argument4") && bg.mode == ByGroupMode.AT_MOST_ONE) {
          df.addAndCombine(rel, bg, (a1, a2) -> {
            return new MultiAtMost1((ByGroup) a1, (ByGroup) a2);
          });
        } else {
          df.add(rel, bg);
        }
      }
      return df;
    }

    // TODO Switch to Relation as key, it has better and faster hash/eq
    private Map<String, DecisionFunction> rel2df = new HashMap<>();
    
    public DecisionFunction getDecisionFunction(HypEdge e) {
      DecisionFunction df = rel2df.get(e.getRelation().getName());
      if (df == null)
        return DEFAULT;
      return df;
    }

    public List<Object> getBucket(HypEdge e) {
//      System.out.println("[getBucket] " + e);
      List<Object> key = new ArrayList<>();
      key.add(e.getRelation());
      DecisionFunction df = rel2df.get(e.getRelation().getName());
      if (df != null) {
        // There will be some facts like startDoc(docId) which do not really have
        // a bucketing and wont be in rel2df. For those, just use the relation as
        // the bucket.
        key.addAll(((ByGroup) df).getKey(e));
      }
      return key;
    }

    /** Returns the number of relation this explicitly tracks */
    public int size() {
      return rel2df.size();
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("(Dispatch");
      for (Entry<String, DecisionFunction> x : rel2df.entrySet())
        sb.append(" " + x.getKey() + "=" + x.getValue());
      sb.append(')');
      return sb.toString();
    }

    public DecisionFunction get(String relation) {
      return rel2df.get(relation);
    }

    public List<Entry<String, DecisionFunction>> entries() {
      return new ArrayList<>(rel2df.entrySet());
    }

    /**
     * Assumes at most one DecisionFunction per Relation.
     */
    public void add(Relation rel, DecisionFunction df) {
      Log.info("[main] rel=" + rel.getName() + " df=" + df);
      Object old = rel2df.put(rel.getName(), df);
      if (old != null) {
        throw new IllegalStateException("rel=" + rel.getName()
          + " has two decision functions, " + old + " and " + df);
      }
    }

    /**
     * If you will have more than one decision function per Relation, use this
     * method and provide a function to combine an existing DecisionFunction
     * (first argument to given function) and a new DecisionFunction (second arg).
     */
    public void addAndCombine(Relation rel, DecisionFunction df, BiFunction<DecisionFunction, DecisionFunction, DecisionFunction> combine) {
      DecisionFunction old = rel2df.put(rel.getName(), df);
      if (old != null) {
        DecisionFunction comb = combine.apply(old, df);
        Log.info("[main] rel=" + rel.getName() + " df=" + comb);
        rel2df.put(rel.getName(), comb);
      } else {
        Log.info("[main] rel=" + rel.getName() + " df=" + df);
      }
    }

    @Override
    public void clear() {
      for (DecisionFunction df : rel2df.values())
        df.clear();
    }

    @Override
    public Boolean decide(HypEdge e, Adjoints s) {
      throw new RuntimeException("dont use this");
    }

    @Override
    public Pair<Boolean, Adjoints> decide2(HypEdge e, Adjoints s) {
      String key = e.getRelation().getName();
      DecisionFunction df = rel2df.getOrDefault(key, DEFAULT);
      return df.decide2(e, s);
    }
  }

  /**
   * Takes the first DecisionFunction to fire.
   *
   * DO NOT USE for {@link DecisionFunction}s which are stateful (and assume that
   * a message will always be delivered via decide() without being interrupted).
   * See {@link Unanimous} for this case.
   *
   * Say you have:
   * 1) Exactly1 argument4(t,f,s,k) [t,s]
   * 2) Exactly1 argument4(t,f,s,k) [t,k]
   *
   * If they are put in series, 1 will accept/reject facts without 2 ever seeing
   * anything. If 1 let SOME but not ALL/NONE through, like AtLeast1, then 2 will
   * get the wrong information.
   */
  public static class Cascade implements DecisionFunction {
    private DecisionFunction cur;
    private DecisionFunction next;

    public Cascade(DecisionFunction cur, DecisionFunction next) {
      this.cur = cur;
      this.next = next;
    }

    public DecisionFunction getCur() {
      return cur;
    }
    public DecisionFunction getNext() {
      return next;
    }

    @Override
    public Boolean decide(HypEdge e, Adjoints s) {
      Boolean b = cur.decide(e, s);
      if (b != null)
        return b;
      if (next != null)
        return next.decide(e, s);
      return null;
    }

    @Override
    public Pair<Boolean, Adjoints> decide2(HypEdge e, Adjoints s) {
      Pair<Boolean, Adjoints> b = cur.decide2(e, s);
      if (b.get1() != null)
        return b;
      if (next != null)
        return next.decide2(e, s);
      return new Pair<>(null, null);
    }

    @Override
    public void clear() {
      cur.clear();
      next.clear();
    }

    @Override
    public String toString() {
      if (next == null)
        return cur + "";
      return cur + " and then " + next;
    }
  }

  /**
   * NOTE: This doesn't really implement what I had in mind in terms of
   * writing out multiple constraints and adding their violation.
   *
   * First major change would be to split DecisionFunction into a decider
   * component (a la decide rather than decide2) and a loss/violation component.
   *
   * The loss/violation component would only return error Adjoints of the
   * form max(0, loss(goldFact,autoFact) + score(goldFact) - score(autoFact))
   */
  public static class MultiAtMost1 implements DecisionFunction {
    private ByGroup left, right;
    private Uberts u;

    public MultiAtMost1(ByGroup atLeastOneLeft, ByGroup atLeastOneRight) {
      Log.info("[main] combining " + atLeastOneLeft + " and " + atLeastOneRight);
      if (atLeastOneLeft.mode != ByGroupMode.AT_MOST_ONE || atLeastOneRight.mode != ByGroupMode.AT_MOST_ONE)
        throw new IllegalArgumentException();
      this.left = atLeastOneLeft;
      this.right = atLeastOneRight;
      this.u = right.u;
    }

    @Override
    public String toString() {
      return "(MultiAtLeast1 " + left + " " + right + ")";
    }

    @Override
    public void clear() {
      left.clear();
      right.clear();
    }

    @Override
    public Boolean decide(HypEdge e, Adjoints s) {
      throw new RuntimeException("dont use this, see decide2");
    }

    @Override
    public Pair<Boolean, Adjoints> decide2(HypEdge e, Adjoints s) {
      Pair<Boolean, Adjoints> a = left.decide2(e, s);
      Pair<Boolean, Adjoints> b = right.decide2(e, s);

      boolean yhat = a.get1() && b.get1();
      boolean y = u.getLabel(e);

      Adjoints reason = (y || yhat) ? s : new Adjoints.WithLearningRate(0, s);
      return new Pair<>(yhat, reason);
    }
  }

  /**
   * Super class for AtLeastOne, AtMostOne, ExactlyOne.
   *
   * ONLY use this class when there is an "easyFirst" term in your {@link AgendaPriority}.
   * These don't really have the declarative meaning that they appear to have,
   * but rather an imperative one along the lines of "take the first one" (for
   * EXACTLY_ONE for example). If you don't include an easyFirst term, then the
   * first is not guaranteed to be the best.
   *
   * Only applies a fact-at-a-time.
   * Meaning that in enforcing "at least one Y per X",
   * Y is characterized as any fact of a given relation and
   * X is characterized as a list of arguments to Y.
   *
   * NOTE: This makes an assumption that if decide(e1, ...) is called before
   * decide(e2, ...), then e1 was actually added to the state... which isn't
   * really true.
   *
   * WARNING: This class is not compatible with {@link Uberts#dbgRunInference()}
   * because it doesn't respect the {@link DecisionFunction}s choice as final.
   * For example, the EXACTLY_ONE function assumes that when it returns true
   * that that edge will actually be added to the state.
   */
  public static class ByGroup implements DecisionFunction, NewStateEdgeListener {

    public static enum ByGroupMode {
      AT_LEAST_ONE,
      AT_MOST_ONE,
      EXACTLY_ONE,
    }

    /**
     * Must match <ByGroupMode>:<Term>(:<arg>)+
     * e.g. "EXACTLY_ONE:argument4(t,f,s,k):t:k"
     */
    public static ByGroup parse(String description, Uberts u) {
      String[] items = description.trim().split(":", 2);
      if (items.length < 2)
        throw new IllegalArgumentException("incorrect format: " + description);
      ByGroupMode m = ByGroupMode.valueOf(items[0]);
      return new ByGroup(m, items[1], u);
    }

    // RHS of rule which this applies to
    private Relation relation;

    // List of argument positions in this.relation which form the key of the
    // at least one constraint, e.g. indexOf(t) in predicate2(t,f) ensures that
    // there is at least one predicate2(t,f) fact for every distinct t value.
    private int[] keyArgs;

    // A set of lists of argument (determined by keyArgs).
    // E.g. for "at least one predicate2(t,f) per event1(t)", we might use
    private Set<List<Object>> observedKeys;
    private LinkedHashMap<List<Object>, Pair<HypEdge, Adjoints>> firstGoldInBucket;
    private LinkedHashMap<List<Object>, Pair<HypEdge, Adjoints>> firstPredInBucket;
    private LinkedHashMap<List<Object>, List<Pair<HypEdge, Adjoints>>> allInBucket;

    private Map<List<Object>, Pair<HypEdge, Adjoints>> lastPredInGroup;

    private Map<List<Object>, List<Adjoints>> dbgScoresInBucket;

    private ByGroupMode mode;

    private Uberts u;

    // If true check that all of the scores that you see (in popped order) are
    // non-increasing. If this is true than you solve the argmax properly. This
    // can be violated if we choose a fact f1 as the argmax of a group, and then
    // adding it to the state forces global features to fire raising the score
    // of f2, which is also in the group, making score(f2)>score(f1), meaning
    // that the argmax was correct at the time but has changed.
    // f1 and f2 do not have to be in the same group, there could be a chain of
    // facts whose global features affect each other, leading in a cycle which
    // leads to changing the argmax of a group.
    // I DON'T THINK THIS IS A PROBLEM because of an analogy to a simpler
    // transition system in which all of the actions and global features are
    // computed at every timestep. In fact, I used to have this, with an argmax
    // over all (t,f,s,k) facts!
    private boolean checkScoresNonIncreasing = false;

    /**
     * @param description should look like: <term>(:<varName>)+, e.g. "predicate2(t,f):t"
     */
    public ByGroup(ByGroupMode mode, String description, Uberts u) {
      this.u = u;
      this.mode = mode;
      this.observedKeys = new HashSet<>();
      this.firstPredInBucket = new LinkedHashMap<>();
      this.firstGoldInBucket = new LinkedHashMap<>();
      this.allInBucket = new LinkedHashMap<>();
      this.lastPredInGroup = new HashMap<>();
      this.dbgScoresInBucket = new HashMap<>();
      String[] parts = description.split(":");
      assert parts.length >= 2;
      keyArgs = new int[parts.length - 1];
      Term t = Term.parseTerm(parts[0], u);
      if (t.rel == null)
        throw new RuntimeException("no relation named: " + t.relName);
      relation = t.rel;
      for (int i = 0; i < keyArgs.length; i++) {
        keyArgs[i] = t.indexOfArg(parts[i+1]);
        if (keyArgs[i] < 0) {
          throw new RuntimeException("could not find argument named "
              + parts[i+1] + " in term " + t);
        }
      }
//      u.addNewStateEdgeListener(this);
    }

    public ByGroup(ByGroupMode mode, Relation relation, int[] keyArgs) {
      if (keyArgs.length >= relation.getNumArgs()) {
        throw new IllegalArgumentException("too many keys, can be no more than "
            + "n-1 where n is the number of args to the given Relation");
      }
      Set<Integer> uniq = new HashSet<>();
      for (int a : keyArgs) {
        if (a < 0 || a >= relation.getNumArgs()) {
          throw new IllegalArgumentException("invalid argument position " + a
              + " for " + relation.getName() + " (" + relation.getNumArgs() + " arguments)");
        }
        if (!uniq.add(a)) {
          throw new IllegalArgumentException("key args to " + relation.getName()
          + " must be uniq: " + Arrays.toString(keyArgs));
        }
      }
      this.mode = mode;
      this.relation = relation;
      this.keyArgs = keyArgs;
      this.observedKeys = new HashSet<>();
      this.firstPredInBucket = new LinkedHashMap<>();
      this.firstGoldInBucket = new LinkedHashMap<>();
      this.allInBucket = new LinkedHashMap<>();
      this.dbgScoresInBucket = new HashMap<>();
    }

    public ByGroupMode getMode() {
      return mode;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append('(');
      sb.append(mode);
      sb.append(' ');
      sb.append(relation.getName());
      sb.append(" per ");
      if (keyArgs.length == 1) {
        String t = relation.getTypeForArg(keyArgs[0]).getName();
        sb.append(keyArgs[0] + ":");
        sb.append(t);
      } else {
        sb.append('(');
        for (int i = 0; i < keyArgs.length; i++) {
          if (i > 0)
            sb.append(',');
          String t = relation.getTypeForArg(keyArgs[i]).getName();
          sb.append(keyArgs[i] + ":");
          sb.append(t);
        }
        sb.append(')');
      }
      sb.append(')');
      return sb.toString();
    }

    @Override
    public void clear() {
      if (Uberts.DEBUG > 2)
        Log.info("CLEAR");
      observedKeys.clear();
      firstPredInBucket.clear();
      firstGoldInBucket.clear();
      allInBucket.clear();
      lastPredInGroup.clear();
      dbgScoresInBucket.clear();
    }

    public List<Pair<HypEdge, Adjoints>> getAllInBucket(List<Object> key) {
      return allInBucket.get(key);
    }

    public LinkedHashMap<List<Object>, Pair<HypEdge, Adjoints>> getFirstPredInBucket() {
      LinkedHashMap<List<Object>, Pair<HypEdge, Adjoints>> m = new LinkedHashMap<>(firstPredInBucket);
      return m;
    }

    public Pair<HypEdge, Adjoints> getFirstPredInBucket(List<Object> key) {
      return firstPredInBucket.get(key);
    }

    public LinkedHashMap<List<Object>, Pair<HypEdge, Adjoints>> getFirstGoldInBucket() {
      LinkedHashMap<List<Object>, Pair<HypEdge, Adjoints>> m = new LinkedHashMap<>(firstGoldInBucket);
      return m;
    }

    public Pair<HypEdge, Adjoints> getFirstGoldInBucket(List<Object> key) {
      return firstGoldInBucket.get(key);
    }

    @Override
    public Boolean decide(HypEdge e, Adjoints s) {
      if (e.getRelation() != relation)
        return null;  // Doesn't apply
      List<Object> key = getKey(e);

      if (checkScoresNonIncreasing)
        checkScoresInGroupSorted(key, e, s);

      boolean newEdge = !observedKeys.contains(key);
      switch (mode) {
      case AT_LEAST_ONE:
        return newEdge ? true : null;
      case AT_MOST_ONE:
        return !newEdge ? false : null;
      case EXACTLY_ONE:
        return newEdge;
      default:
        throw new RuntimeException("unknown mode:" + mode);
      }
    }

    public List<Object> getKey(HypEdge e) {
      List<Object> key = new ArrayList<>(keyArgs.length);
      for (int i = 0; i < keyArgs.length; i++)
        key.add(e.getTail(keyArgs[i]));
      return key;
    }

    @Override
    public Pair<Boolean, Adjoints> decide2(HypEdge e, Adjoints s) {
      if (e.getRelation() != relation)
        return new Pair<>(null, null);  // Doesn't apply
      List<Object> key = getKey(e);

      if (checkScoresNonIncreasing)
        checkScoresInGroupSorted(key, e, s);

      final Pair<HypEdge, Adjoints> p = new Pair<>(e, s);
      boolean first = !firstPredInBucket.containsKey(key);
      boolean y = u.getLabel(e);
      boolean yhat;
      switch (mode) {
      case AT_LEAST_ONE:
        yhat = first || s.forwards() > 0;
        break;
      case AT_MOST_ONE:
        yhat = first && s.forwards() > 0;
        break;
      case EXACTLY_ONE:
        yhat = first;
        break;
      default:
        throw new RuntimeException("unknown mode:" + mode);
      }
      if (yhat && first)
        firstPredInBucket.put(key, p);
      if (y && !firstGoldInBucket.containsKey(key))
        firstGoldInBucket.put(key, p);

      List<Pair<HypEdge, Adjoints>> all = allInBucket.get(key);
      if (all == null) {
        all = new ArrayList<>();
        allInBucket.put(key, all);
      }
      all.add(p);

      Adjoints updateable = (y || yhat) ? s : new Adjoints.WithLearningRate(0, s);
      return new Pair<>(yhat, updateable);
    }

    /**
     * The problem with this is that this may or may not be called depending on
     * what {@link Uberts#dbgRunInference()} decides to do. If it is run with
     * oracle, then FPs may not be added to the state, leading to funny and hard
     * to reason about states of the data structures in this class.
     *
     * A simpler solution is to just take the label when decide2 is called.
     * The assumption that decide2 is called on every relevant edge is reasonable,
     * the assumption that addedToState is called forall edges is not.
     */
    @Override
    public void addedToState(HashableHypEdge he, Adjoints score, Boolean y) {
    }

    private void checkScoresInGroupSorted(List<Object> key, HypEdge e, Adjoints s) {
      Pair<HypEdge, Adjoints> prev = lastPredInGroup.put(key, new Pair<>(e, s));
      if (prev != null && s.forwards() > prev.get2().forwards()) {
        throw new RuntimeException("In the group defined by " + toString()
          + ", key=" + key
          + ". We just popped " + e + " with a score of " + s.forwards() + " " + StringUtils.trunc(s, 100)
          + ", but this is higher than the last score for a fact in this group, "
          + prev.get1() + " with a score of " + prev.get2().forwards() + " " + StringUtils.trunc(prev.get2(), 100)
          + ". This class will only work if facts are sorted with respect to"
          + " model score within every group."
          + " Check that your agenda priority doesn't contain any terms which"
          + " affect items within a group.");
      }
    }
  }


  /**
   * Checks whether score(edge) > constant, optionally only applying to one Relation.
   */
  public static class Constant implements DecisionFunction {
    private Relation relevant;
    private double threshold;
    public Constant(double threshold) {
      this.relevant = null;
      this.threshold = threshold;
    }
    public Constant(Relation relevant, double threshold) {
      if (relevant == null)
        throw new IllegalArgumentException();
      this.relevant = relevant;
      this.threshold = threshold;
    }
    @Override
    public Boolean decide(HypEdge e, Adjoints s) {
      if (relevant != null && e.getRelation() != relevant)
        return null;
      double f = s.forwards();
      return f > threshold;
    }
    @Override
    public Pair<Boolean, Adjoints> decide2(HypEdge e, Adjoints s) {
      if (relevant != null && e.getRelation() != relevant)
        return new Pair<>(null, null);
      double f = s.forwards();
      return new Pair<>(f > threshold, s);
    }
    @Override
    public void clear() {
      // no-op
    }
    @Override
    public String toString() {
      if (relevant != null) {
        return String.format("(DecisionFunction.Constant %s %+.2f)",
            relevant.getName(), threshold);
      }
      return String.format("(DecisionFunction.Constant %+.2f)", threshold);
    }
  };
}
