package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda.AgendaItem;
import edu.jhu.hlt.uberts.auto.Term;

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
   * Only applies a fact-at-a-time.
   * Meaning that in enforcing "at least one Y per X",
   * Y is characterized as any fact of a given relation and
   * X is characterized as a list of arguments to Y.
   *
   * NOTE: This makes an assumption that if decide(e1, ...) is called before
   * decide(e2, ...), then e1 was actually added to the state... which isn't
   * really true.
   */
  public static class ByGroup implements DecisionFunction {

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

    private ByGroupMode mode;

    /**
     * @param description should look like: <term>(:<varName>)+, e.g. "predicate2(t,f):t"
     */
    public ByGroup(ByGroupMode mode, String description, Uberts u) {
      this.mode = mode;
      this.observedKeys = new HashSet<>();
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
      if ("argument4".equals(relation.getName()) && !observedKeys.isEmpty())
        System.currentTimeMillis();
      observedKeys.clear();
    }

    @Override
    public Boolean decide(HypEdge e, Adjoints s) {
      if (e.getRelation() != relation)
        return null;  // Doesn't apply
      // See if we've already let one through
      List<Object> key = new ArrayList<>(keyArgs.length);
      for (int i = 0; i < keyArgs.length; i++)
        key.add(e.getTail(keyArgs[i]));

      // This is not enough!
      // I have assumed that observedKeys is the set of edges that we put onto
      // the state, but that may not be the case.
      // E.g. score(e1) = -2, score(e2) = -3, etc
      // assume key(e1) == key(e2)
      // AT_MOST_ONE on e1 => new addition of e1 and return null
      // AT_MOST_ONE on e2 => not new, return false
      // This is actually probably not a problem since nothing is going to come
      // in between popping e1 and e2 which might change the score of e2...
      boolean newEdge = observedKeys.add(key);

      switch (mode) {
      case AT_LEAST_ONE:
        return newEdge ? true : null;
      case AT_MOST_ONE:
        return !newEdge ? false : null;
      case EXACTLY_ONE:
        if (DEBUG > 1)
          System.out.println("[Exactly1] first=" + newEdge + "\t" + e + "\t" + toString());
        return newEdge;
      default:
        throw new RuntimeException("unknown mode:" + mode);
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
//      if (DEBUG > 0) {
//        if (relevant != null && relevant.getName().equals("srl3"))
//          System.currentTimeMillis();
//        System.out.println("[Constant] keep=" + (f>threshold) + "\t" + e + "\t" + toString());
//      }
      return f > threshold;
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
