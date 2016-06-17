package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
   * Takes the first DecisionFunction to fire.
   */
  public static class Cascade implements DecisionFunction {
    private DecisionFunction cur;
    private DecisionFunction next;

    public Cascade(DecisionFunction cur, DecisionFunction next) {
      this.cur = cur;
      this.next = next;
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
   */
  public static class ByGroup implements DecisionFunction {

    public static enum ByGroupMode {
      AT_LEAST_ONE,
      AT_MOST_ONE,
      EXACTLY_ONE,
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
      sb.append("(AtLeastOne ");
      sb.append(relation.getName());
      sb.append(" per ");
      if (keyArgs.length == 1) {
        String t = relation.getTypeForArg(keyArgs[0]).getName();
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
      boolean newEdge = observedKeys.add(key);
      switch (mode) {
      case AT_LEAST_ONE:
        return newEdge ? true : null;
      case AT_MOST_ONE:
        return !newEdge ? false : null;
      case EXACTLY_ONE:
        if ("argument4".equals(relation.getName()))
          System.currentTimeMillis();
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
      return s.forwards() > threshold;
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
