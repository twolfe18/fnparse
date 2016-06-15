package edu.jhu.hlt.uberts.factor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.Agenda.AgendaItem;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Term;

/**
 * Ensures that a set of items S has 0 < argmax_{f in S} score(f) at PUSH TIME.
 *
 * S is characterized as being all facts produced by a single rule (i.e. all
 * facts in S have the same type/Relation). Currently I only have one way to
 * build an edge type, if this changed, then revisit how to enforce this
 * constraint.
 *
 * @author travis
 */
public class AtLeast1Local implements PreAgendaAddMapper {
  public static int DEBUG = 0;

  // Need a pointer to this to be able to add/remove edges behind the scenes.
  private Agenda agenda;

  // RHS of rule which this applies to
  private Relation relation;

  // List of argument positions in this.relation which form the key of the
  // at least one constraint, e.g. indexOf(t) in predicate2(t,f) ensures that
  // there is at least one predicate2(t,f) fact for every distinct t value.
  private int[] keyArgs;

  // All edges which have run through this class.
  // Mostly for debugging, ensuring that clear is being called.
  private List<HashableHypEdge> seen;
  private int check = 32;

  // Keys are e.g. t or event1(t)
  // TODO Make this general (supply a key-extracting function)
  private Map<Object, ModifiedScore> mods;

  /**
   * @param description should look like: <term>(:<varName>)+, e.g. "predicate2(t,f):t"
   */
  public AtLeast1Local(String description, Uberts u) {
    this.agenda = u.getAgenda();
    this.seen = new ArrayList<>();
    this.mods = new HashMap<>();
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

  public AtLeast1Local(Agenda agenda, Relation relation, int[] keyArgs) {
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
    this.agenda = agenda;
    this.seen = new ArrayList<>();
    this.mods = new HashMap<>();
    this.relation = relation;
    this.keyArgs = keyArgs;
  }

  @Override
  public Adjoints map(HashableHypEdge e, Adjoints a) {
//    if (!"predicate2".equals(e.getEdge().getRelation().getName()))
//      return a;
    if (relation != e.getEdge().getRelation())
      return a;
    if (DEBUG > 0) {
      System.out.println("[AtLeast1] " + e + " " + a);
    }
    seen.add(e);
    if (seen.size() > check) {
      Log.info("[memLeak] seen.size=" + seen.size());
      check *= 1.5;
    }

    // t in predicate2(t,f)
    // HypNode is hashable.
//    Object key = e.getEdge().getTail(0);
    List<HypNode> key = new ArrayList<>(keyArgs.length);
    for (int i = 0; i < keyArgs.length; i++)
      key.add(e.getEdge().getTail(keyArgs[i]));

    // If this is our best score so far AND it is below 0
    ModifiedScore m = mods.get(key);
    if ((m == null && a.forwards() <= 0)
        || (m != null && a.forwards() > m.oldScore.forwards())) {
      // We just had a case where 
      if (m != null) { //&& agenda.contains(m.edge)) {
        AgendaItem ai = agenda.remove(m.edge);
        if (DEBUG > 0) {
          System.out.println("[AtLeast1] removed old: " + ai);
        }
        if (Adjoints.uncacheIfNeeded(ai.score) != m.newScore) {
          throw new RuntimeException("Make sure you have no other global factors"
              + " which affect " + relation.getName() + ", which is not allowed"
                  + "with " + getClass().getName() + ". "
                      + "old.score=" + ai.score + " new.score=" + m.newScore);
        }
        agenda.add(m.edge, m.oldScore);
      }
      // Store new edge
      m = new ModifiedScore(e, a);
      mods.put(key, m);
      if (DEBUG > 0) {
        System.out.println("[AtLeast1] added new: " + m.newScore);
      }
      return m.newScore;
    } else {
      // Then this is not the highest score, be identity function
      if (DEBUG > 0) {
        System.out.println("[AtLeast1] not the best, identify function");
      }
      return a;
    }
  }

  @Override
  public void clear() {
    seen.clear();
    mods.clear();
  }

  @Override
  public String toString() {
    return "(AtLeast1Local nEdge=" + seen.size() + " nKey=" + mods.size() + ")";
  }

  /**
   * Represents the argmax over a set of edges. Currently I only modify the
   * highest scoring edge (on the assumption that AtLeast1 => AtMost1, which
   * is true now, but not a good idea in general). If I later choose to track
   * all other scores, I can do that with this class.
   *
   * Note: You might be tempted to put an Adjoints which has a very late-binding
   * value for the adjustment amount (in order to get around doing an add/remove
   * for every edge in the set), but the agenda won't know that the score changed,
   * which will lead to bugs. Just add/remove every time!
   */
  static class ModifiedScore {
    HashableHypEdge edge;
    Adjoints oldScore;
    Adjoints newScore;  // on the agenda
    public ModifiedScore(HashableHypEdge edge, Adjoints oldScore) {
      this.edge = edge;
      this.oldScore = oldScore;
      if (oldScore.forwards() > 0) {
        this.newScore = oldScore;
      } else {
        double v = -(oldScore.forwards() - 1e-8);
        this.newScore = Adjoints.sum(new Adjoints.Constant(v), oldScore);
      }
    }
    public boolean isModified() {
      return oldScore != newScore;
    }
  }
}
