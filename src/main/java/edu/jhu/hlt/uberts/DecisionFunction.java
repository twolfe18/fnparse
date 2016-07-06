package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda.AgendaItem;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.Uberts.NewStateEdgeListener;
import edu.jhu.hlt.uberts.auto.Term;
import edu.jhu.hlt.uberts.auto.UbertsLearnPipeline;
import edu.jhu.hlt.uberts.auto.UbertsLearnPipeline.TrainMethod;
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

    /**
     * Accepts white-space-separated items which can be parsed by {@link
     * ByGroup#parse(String)}. Composes them into a {@link Cascade}.
     */
    public static DecisionFunction parseMany(String description, Uberts u) {
      String[] rules = description.split("\\s+");
      DecisionFunction.Unanimous df = new DecisionFunction.Unanimous();
      for (int i = 0; i < rules.length; i++)
        df.add(parse(rules[i], u));
      return df;
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
    private Map<List<Object>, Pair<HypEdge, Adjoints>> firstGoldInBucket;
    private Map<List<Object>, Pair<HypEdge, Adjoints>> firstPredInBucket;

    private ByGroupMode mode;

    /**
     * @param description should look like: <term>(:<varName>)+, e.g. "predicate2(t,f):t"
     */
    public ByGroup(ByGroupMode mode, String description, Uberts u) {
      this.mode = mode;
      this.observedKeys = new HashSet<>();
      this.firstPredInBucket = new HashMap<>();
      this.firstGoldInBucket = new HashMap<>();
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

      // Try to verify that there is an easyFirst term in the agendaPriority.
      ExperimentProperties config = ExperimentProperties.getInstance();
      assert config.getString("agendaPriority").toLowerCase().contains("easyfirst")
          && !config.getBoolean("ignoreByGroupErrMsg", false)
          : "You must include an easyFirst term in agendaPriority to at least break ties if you want to use this class";

      u.addNewStateEdgeListener(this);
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
      this.firstPredInBucket = new HashMap<>();
      this.firstGoldInBucket = new HashMap<>();
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
      observedKeys.clear();
      firstPredInBucket.clear();
      firstGoldInBucket.clear();
    }

    @Override
    public Boolean decide(HypEdge e, Adjoints s) {
      if (e.getRelation() != relation)
        return null;  // Doesn't apply
      List<Object> key = new ArrayList<>(keyArgs.length);
      for (int i = 0; i < keyArgs.length; i++)
        key.add(e.getTail(keyArgs[i]));
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

    @Override
    public Pair<Boolean, Adjoints> decide2(HypEdge e, Adjoints s) {
      if (e.getRelation() != relation)
        return new Pair<>(null, null);  // Doesn't apply

      List<Object> key = new ArrayList<>(keyArgs.length);
      for (int i = 0; i < keyArgs.length; i++)
        key.add(e.getTail(keyArgs[i]));

      switch (mode) {
      case AT_LEAST_ONE:
        // This can only force a FP
        // At the time of returning true here, we will not know what the next
        // edge which will fall into this bucket which is gold==true whose
        // score we could shift to make this constraint satisfied.
        // ALSO, if that other edges score>0, then AT_LEAST_ONE is not relevant,
        // maybe only wait of another edge whose score<=0.
//        return newEdge ? true : null;
        throw new RuntimeException("implement me!");
      case AT_MOST_ONE:
//        if (true) {
//          throw new RuntimeException("this implementation is broken in cases "
//              + "where there is no gold edge, because the first will get through "
//              + "and receive a score-- update on a FP");
//        }
        // NOTE: The solution to this is to use MAX_VIOLATION rather than DAGGER1

        boolean newEdge = !observedKeys.contains(key);
        if (newEdge) {
          // No opinion: constraint not violated
          return new Pair<>(null, null);
        } else {

          // See my notes in ~/research/2016-spring/daily-notes/2016-07-01.txt

          // Return lazy Adjoints so that all edges can be added to make appropriate
          // updates to firstPredInBucket and firstGoldInBucket
          if (UbertsLearnPipeline.trainMethod == TrainMethod.MAX_VIOLATION) {
            if (Uberts.LEARN_DEBUG)
              Log.info(mode + " edge=" + e);
            return new Pair<>(false, new IncludeLossAdjoints(Adjoints.Constant.ZERO, false));
          } else if (UbertsLearnPipeline.trainMethod == TrainMethod.DAGGER1) {
            return new Pair<>(false, new Adjoints() {
              @Override
              public double forwards() {
                return s.forwards();
              }
              @Override
              public void backwards(double dErr_dForwards) {
                assert dErr_dForwards < 0 : "should be FN, right?";
                // Only update if this was the first (presumably MV) or gold
                Pair<HypEdge, Adjoints> p = firstPredInBucket.get(key);
                Pair<HypEdge, Adjoints> g = firstGoldInBucket.get(key);

                if (g == null) {
                  // If there is no gold, then pushing down the score of some
                  // mistake can't possibly lead to a correct decision.
                  // This can happen e.g. if a path to a gold label was pruned
                  // by some other fact. In this case that pruning step should
                  // receive an update, not this step which can do nothing to recover.
                  return;
                }

                assert p != g : "this implies no mistake, yet dErr_dForwards=" + dErr_dForwards;
                if (p != null || g != null) {
                  assert (e == p.get1()) ^ (e == g.get1()) : "this edge should be pred or gold";
                  s.backwards(+dErr_dForwards);
                }
              }
            });
          } else {
            throw new RuntimeException("implement me: " + UbertsLearnPipeline.trainMethod);
          }
        }
      case EXACTLY_ONE:
        assert UbertsLearnPipeline.trainMethod == TrainMethod.DAGGER1
          : "not up to date for: " + UbertsLearnPipeline.trainMethod;
        Pair<HypEdge, Adjoints> p = firstPredInBucket.get(key);
        Pair<HypEdge, Adjoints> g = firstGoldInBucket.get(key);

        if (p == null) {
          assert g == null;
          return new Pair<>(true, s);
        } else if (g == null) {
          return new Pair<>(false, s);
        } else {
          System.out.println("returnin no-backprop for " + e);
          Adjoints a = new Adjoints.WithLearningRate(0, s);
          a = new Adjoints.Named("BLOCKED: " + e, a);
          return new Pair<>(false, a);
        }

      default:
        throw new RuntimeException("unknown mode:" + mode);
      }
    }

    @Override
    public void addedToState(HashableHypEdge he, Adjoints score, Boolean y) {
      HypEdge e = he.getEdge();
      if (e.getRelation() != relation)
        return;  // Doesn't apply
      List<Object> key = new ArrayList<>(keyArgs.length);
      for (int i = 0; i < keyArgs.length; i++)
        key.add(e.getTail(keyArgs[i]));
      Pair<HypEdge, Adjoints> x = new Pair<>(he.getEdge(), score);

      boolean added = observedKeys.add(key);
      if (Uberts.LEARN_DEBUG)
        Log.info("key=" + key + " added=" + added + " edge=" + he.getEdge());

      if (firstPredInBucket.get(key) == null)
        firstPredInBucket.put(key, x);

      if (y && firstGoldInBucket.get(key) == null)
        firstGoldInBucket.put(key, x);
    }
  }


  /**
   * This is a hack so that {@link ByGroup} can tell
   * {@link Uberts#maxViolationPerceptron(Map)} whether a particular
   * actions's loss should be included when computing the violation.
   *
   * NOTE: The proper way to fix this is to:
   * 1) store actions on the agenda rather than facts (need this so that costFP vs costFN can be applied appropriately)
   * 2) have a LocalFactor which adds in the loss to the score of actions for loss augmented inference
   * 3) in contrast with 2, probably keep Agenda.Mode.ORACLE so that you don't have to worry about global features making a lossy action score too high
   */
  public static class IncludeLossAdjoints implements Adjoints {
    private Adjoints wrapped;
    private boolean includeLoss;

    public IncludeLossAdjoints(Adjoints wrapped, boolean includeLoss) {
      this.wrapped = wrapped;
      this.includeLoss = includeLoss;
    }

    @Override
    public String toString() {
      return "(IncludeLoss " + includeLoss + " " + wrapped + ")";
    }

    public boolean includeLoss() {
      return includeLoss;
    }

    @Override
    public double forwards() {
      return wrapped.forwards();
    }

    @Override
    public void backwards(double dErr_dForwards) {
      wrapped.backwards(dErr_dForwards);
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
