package edu.jhu.hlt.uberts.rules;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.commons.collections4.map.LinkedMap;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.IntTrip;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.auto.Rule;
import edu.jhu.hlt.uberts.auto.Term;
import edu.jhu.hlt.uberts.auto.Trigger;

public class Env {

  /**
   * Connects two trie nodes, enforces 0 or more constraints, and binds one or
   * more {@link Term}s to {@link HypEdge}s.
   */
  public static interface Edge3 {
    /** Facts which satisfy this edge (constraint) witnessed by the binding b */
    List<HypEdge> satisfy(Bindings b);

    /**
     * Can be used to take the conjunction of two constraints. For example, lets
     * say that you had: "foo(x) & bar(y) & baz(x,y)" and you had two constraints:
     * cons1={baz.x == foo.x}, cons2={baz.y == bar.y}.
     *
     * Both these constraints induce a call to State.match which returns a list
     * of baz edges. Take the intersection of those two.
     *
     * NOTE: Since the state is monotonically increasing (indices are append-only),
     * the order of edges returned by State.match are both chronological in
     * order of addition. This can make that intersection particularly fast
     * without the use of a HashSet.
     */
    public static class Intersect implements Edge3 {
      private Edge3 cons1, cons2;
      public Intersect(Edge3 shortCircuitFirst, Edge3 lessSelective) {
        this.cons1 = shortCircuitFirst;
        this.cons2 = lessSelective;
      }
      @Override
      public List<HypEdge> satisfy(Bindings b) {
        // Take the intersection of cons1.satisfy(b) and cons2.satisfy(b)
        List<HypEdge> s1 = cons1.satisfy(b);
        if (s1.isEmpty())
          return s1;
        List<HypEdge> s2 = cons2.satisfy(b);
        if (s2.isEmpty())
          return s2;
        Set<HashableHypEdge> s = new HashSet<>();
        List<HypEdge> intersect = new ArrayList<>();
        for (HypEdge e : s1)
          s.add(new HashableHypEdge(e));
        assert s.size() == s1.size();
        for (HypEdge e : s2)
          if (s.remove(new HashableHypEdge(e)))
            intersect.add(e);
        return intersect;
      }
    }
  }

  /**
   * Matches ALL facts of a certain relation.
   */
  public static class FreeRelation implements Edge3 {
    private Relation r;
    public FreeRelation(Relation r) {
      this.r = r;
    }
    @Override
    public List<HypEdge> satisfy(Bindings b) {
      LL<HypEdge> l = b.getState().match2(r);
      if (l == null)
        return Collections.emptyList();
      return l.toList();
    }
  }

  /**
   * Enforces equality between two variables in a Rule.
   */
  public static class ArgEquality implements Edge3 {
    private Arg from, to;
    public ArgEquality(Arg bound, Arg free) {
      this.from = bound;
      this.to = free;
    }
    @Override
    public List<HypEdge> satisfy(Bindings b) {
      HypNode val = b.get(from);
      LL<HypEdge> sat = b.getState().match(to.argPos, to.r, val);
      if (sat == null)
        return Collections.emptyList();
      return sat.toList();
    }
  }

  /**
   * Stores the triggers so that they can always be matched.
   *
   * NOTE: Previously a {@link Trigger} was thought of as a {@link Rule}s LHS,
   * so when you see the r in (r,t,a), this was a mnemonic for "rule", but
   * really refers to a Trigger's index.
   *
   * This lets you do two things:
   * 1) add triggers
   * 2) find matches with {@link Trie3#match(State, HypEdge, Consumer)}
   *
   * The reason to not have payloads (i.e. think of the trie as a Map) is that
   * I don't want to support a "get" function. The key, {@link Trigger}, is
   * not an efficient way to look up values, and I don't want implementation
   * details about how this class stores triggers to leak through a "get/put"
   * interface.
   *
   * @author travis
   * @param <T> is the type of the payload (what you can store at the accepting states).
   */
  public static class Trie3 {
    // FOR DEBUGGING, null means don't track counts
    public static final Counts<String> EVENT_COUNTS = new Counts<>();
    private static void cnt(String msg) {
      if (EVENT_COUNTS != null)
        EVENT_COUNTS.increment(msg);
    }

    public static int DEBUG = 0;

    private Trie3 root;
    private Trie3 parent; // allows you to compute i from just R

    private Term term;            // Provides variable names
    private Relation rel;         // R (view on term)
    private int relOccurrence;    // i (view on (term, parent))

    // How to get to the next Trie3 node
    // Edge3 can be though of as constraints to check while binding the next fact.
    private List<Edge3> whatToBind;
    // Concrete example why this map should be separate from Edge3s:
    // Rule1 = "foo(x,y) & bar(x,y) => ..."
    // Rule2 = "foo(x,z) & bar(y,z) => ..."
    // Rule1 will require an Edge3 which checks equality for both {x,y}
    // Rule2 will only require an Edge3 to check {z} equality.
    // Both Rule1 and Rule2 will end up in a state where we have just bound a bar fact/HypEdge.
    private Map<Relation, Trie3> children;

    // Maps (R,i,a) <-> (ruleIdx,termIdx,argIdx)
    // Includes an (R,i,a) entry for every Trie3 node between this and ROOT.
    // ONLY NON-NULL AT ROOT. (Global mapping, not node-specific)
    private Rta2Ria varMapping;

    // If non-null, this is an accepting state, and this is the completed rule.
    private Trigger complete;

    public static Trie3 makeRoot() {
      return  new Trie3(null, -1, null, null);
    }

    public Trie3(Term t, int relOccurrence, Trigger completed, Trie3 parent) {
      this.term = t;
      if (t == null) {
        // ROOT
        this.varMapping = new Rta2Ria();
        assert parent == null;
        this.root = this;
      } else {
        this.rel = t.rel;
        assert parent != null;
        this.root = parent;
        while (root.parent != null)
          root = root.parent;
      }
      this.parent = parent;
      this.relOccurrence = relOccurrence;
      this.whatToBind = new ArrayList<>();  // populated as you add rules
      this.children = new HashMap<>();
      this.complete = completed;
      cnt("newTrieNode");
    }

    /**
     * Emit all proofs using facts in the state of the each of the formulas
     * contained in this trie.
     */
    public void match(State s, HypEdge lastFact, Consumer<Match> emit) {
      Trie3 child = children.get(lastFact.getRelation());
      if (child != null) {
        Bindings b = new Bindings(s);
        b.add(lastFact);
        child.match(s, b, emit);
      }
      cnt("match/root");
    }

    private void match(State s, Bindings b, Consumer<Match> emit) {
      assert b.get(new Ri(rel, relOccurrence)) != null
          : "should have bound " + rel.getName() + " by the time you reached this Trie3 node";

      cnt("match");
      if (complete != null) {
        cnt("match/emit");
        emit.accept(this.new Match(b));
      } else {
        cnt("match/internal");
      }

      // Together these loops mean:
      // "for Fact f in State which is consistent with previousBindings+curEqConstraints"
      for (Edge3 e : whatToBind) {
        cnt("match/edge");
        for (HypEdge fact : e.satisfy(b)) {
          cnt("match/fact");
          b.add(fact);
          Trie3 child = children.get(fact.getRelation());
          child.match(s, b, emit);
          b.remove(fact);
        }
      }
    }

    /**
     * Allows you to read bound values for a matched rule using
     * (ruleIdx,termIdx,argIdx) indices instead of the (R,i,a) indices
     * produced during search/binding.
     *
     * Gives access to a {@link Rule} (represents an accepting state in the trie)
     * a {@link Bindings}, and a payload:T.
     */
    public class Match {
      private Bindings bindings;
      public Match(Bindings b) {
        this.bindings = b;
      }
      public HypNode getValue(int termIdx, int argIdx) {
        IntTrip rta = new IntTrip(complete.getIndex(), termIdx, argIdx);
        Arg ria = root.varMapping.get(rta);
        return bindings.get(ria);
      }
      public HypEdge getValue(int termIdx) {
        IntPair rt = new IntPair(complete.getIndex(), termIdx);
        Ri ri = root.varMapping.get(rt);
        HypEdge e = bindings.get(ri);
        assert e != null;
        return e;
      }
      public HypEdge[] getValues() {
        int n = complete.length();
        HypEdge[] edges = new HypEdge[n];
        for (int i = 0; i < n; i++)
          edges[i] = getValue(i);
        return edges;
      }
      public Trigger getTrigger() {
        return complete;
      }
      @Override
      public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("(Match ");
        sb.append(complete.toString());
        for (int i = 0; i < complete.length(); i++) {
          sb.append(' ');
          sb.append(getValue(i));
        }
        sb.append(')');
        return sb.toString();
      }
    }

    public void add(Trigger trigger) {
      if (DEBUG > 1)
        Log.info("ROOT trigger=" + trigger);
      assert term == null : "is this not root?";
      int n = trigger.length();
      BitSet lhsCovered = new BitSet(n);
      // Add n strings, one which starts with each of the n Functor terms in
      // the rule.
      for (int i = 0; i < n; i++) {
        lhsCovered.set(i, true);
        Term l = trigger.get(i);
        Trie3 child = children.get(l.rel);
        if (child == null) {
          if (trigger.length() == 1)
            child = new Trie3(l, 0, trigger, this);
          else
            child = new Trie3(l, 0, null, this);
          children.put(l.rel, child);
        }

        // Add (R,i,a) <-> (r,t,a) entries for arguments of the first term
        // chosen, since we've marked them covered here (won't be added down the stack).
        for (int argIdx : l.getArgIndices()) {
          Arg ria = new Arg(l.rel, 0, argIdx);
          IntTrip rta = new IntTrip(trigger.getIndex(), i, argIdx);
          // Update (R,i,a) <-> (r,t,a) bijection
          if (DEBUG > 1)
            Log.info("adding varMapping: trigger=" + trigger + " ria=" + ria + " rta=" + rta);
          root.varMapping.add(ria, rta);
        }

        child.add(trigger, lhsCovered);
        lhsCovered.set(i, false);
      }
    }

    private void add(Trigger trigger, BitSet lhsCovered) {
      // How do I know i? Which occurrence of R this is?
      // I could walk up the path, or I could pass down a map.
      // I think I'll walk up.
      int nextLhsTermIdx = chooseNextArgPos(trigger, lhsCovered);
      if (nextLhsTermIdx < 0) {
        // We're done
        assert lhsCovered.cardinality() == trigger.length();
        return;
      }
      assert !lhsCovered.get(nextLhsTermIdx);
      Term nextLhsTerm = trigger.get(nextLhsTermIdx);

      int nextLhsTermRelOccurrence = 0;
      for (Trie3 cur = this; cur != null; cur = cur.parent)
        if (nextLhsTerm.rel.equals(cur.rel))
          nextLhsTermRelOccurrence++;

      // Make child, will add later
      Trie3 c = children.get(nextLhsTerm.rel);
      if (c == null) {
        Trigger complete = null;
        if (lhsCovered.cardinality()+1 == trigger.length())
          complete = trigger;
        c = new Trie3(nextLhsTerm, nextLhsTermRelOccurrence, complete, this);
        children.put(nextLhsTerm.rel, c);
      }

      // keys are (r,t,a) and values are (R,i,a) which are bound by parents
      // of this node.
      List<ArgEquality> eqConstraints = new ArrayList<>();
      for (int argIdx : nextLhsTerm.getArgIndices()) {
        // Two views on this variable
        Arg ria = new Arg(nextLhsTerm.rel, nextLhsTermRelOccurrence, argIdx);
        IntTrip rta = new IntTrip(trigger.getIndex(), nextLhsTermIdx, argIdx);

        // Update (R,i,a) <-> (r,t,a) bijection
        // TODO This is un-necessary, can have (R,i) <-> (r,t) bijection, promote to include a trivially (identity)
        if (DEBUG > 0) {
          Log.info("adding varMapping: trigger=" + trigger + " ria=" + ria + " rta=" + rta);
        }
        root.varMapping.add(ria, rta);

        // See if varName is already matched by a parent's (R,i)
        Arg riaDeclaration = c.findParentWhoDefines(ria);
        if (riaDeclaration != null) {
          // This says that we have to ensure that this Arg matches an earlier
          // bound value.
          eqConstraints.add(new ArgEquality(riaDeclaration, ria));
        }
      }

      // Construct new Edge3
      // We have to satisfy all of the ArgEquality constraints.
      Edge3 e;
      if (eqConstraints.isEmpty()) {
        // The next term chosen is totally free, has no bound variables.
        // This is probably a bad choice of next Term.
        // All facts matching the Relation of the next term will be looped over!
        e = new FreeRelation(nextLhsTerm.rel);
      } else if (eqConstraints.size() == 1) {
        // We found one variable in the next Term which has already been bound.
        // We can efficiently check that using State.match(argPos,Relation,value)
        e = eqConstraints.get(0);
      } else {
        // TODO Consider how to order these multiple constraints.
        int n = eqConstraints.size();
        e = eqConstraints.get(n-1);
        for (int i = n-2; i >= 0; i--)
          e = new Edge3.Intersect(eqConstraints.get(i), e);
      }

      lhsCovered.set(nextLhsTermIdx, true);
      whatToBind.add(e);
      c.add(trigger, lhsCovered);
      lhsCovered.set(nextLhsTermIdx, false);
    }

    /**
     * Returns the index of a LHS term which is not yet covered.
     * Rank based on (in order):
     * 1) whether this term has already bound variables
     * 2) how many un-bound terms could be bound by choosing this relation
     * 3) order of appearance as the rule was given (leftmost terms come first)
     */
    private int chooseNextArgPos(Trigger t, BitSet lhsCovered) {
      Log.warn("TODO write optimized implementation!");
      // Just choose the first for now
      int n = t.length();
      for (int i = 0; i < n; i++) {
        if (!lhsCovered.get(i))
          return i;
      }
      return -1;
    }

    /**
     * Find an Arg which is:
     * 1) bound by a (R,i) above this trie node
     * 2) has the same name as the given Arg
     * Or return null if nothing satisfies these constraints.
     */
    private Arg findParentWhoDefines(Arg a) {
      String argName = term.getArgName(a.argPos);
      if (argName == null) {
        assert a.argPos == State.HEAD_ARG_POS;
        return null;
      }
      for (Trie3 cur = parent; cur != null; cur = cur.parent) {
        Term t = cur.term;
        if (t == null) {
          assert cur.parent == null;  // ROOT
          break;
        }
        for (int ai : t.getArgIndices())
          if (argName.equals(t.getArgName(ai)))
            return new Arg(cur.rel, cur.relOccurrence, ai);
      }
      return null;
    }
  }

  public static class Rta2Ria {
    private Ri[][] rt2ri;
    private Arg[][][] rta2ria;
    public Rta2Ria() {
      int maxRules = 32;
      int maxTermsInRule = 16;
      int maxArgsInTerm = 16;
      rt2ri = new Ri[maxRules][maxTermsInRule];
      rta2ria = new Arg[maxRules][maxTermsInRule][maxArgsInTerm];
    }
    public void add(Arg ria, IntTrip rta) {
      rt2ri[rta.first][rta.second] = new Ri(ria.r, ria.rOccurrence);
      rta2ria[rta.first][rta.second][rta.third] = ria;
    }
    public Arg get(IntTrip rta) {
      return rta2ria[rta.first][rta.second][rta.third];
    }
    public Ri get(IntPair rt) {
      return rt2ri[rt.first][rt.second];
    }
  }

  /**
   * R = Relation
   * i = occurrence of that relation in the path from the root to a trie node
   */
  static class Ri {
    Relation r;
    int rOccurrence;
    private int hc;
    public Ri(Relation r, int occurrence) {
      this.r = r;
      this.rOccurrence = occurrence;
      int rhc = r == null ? 0 : r.hashCode();
      this.hc = Hash.mix(rhc, occurrence);
    }
    @Override
    public int hashCode() {
      return hc;
    }
    @Override
    public boolean equals(Object other) {
      if (other instanceof Ri) {
        Ri a = (Ri) other;
        return rOccurrence == a.rOccurrence && r.equals(a.r);
      }
      return false;
    }
    @Override
    public String toString() {
      String rel = r == null ? "null" : r.getName();
      return "(Ri R=" + rel + " i=" + rOccurrence + ")";
    }
  }

  /**
   * (R,i,a) = Ri and an argument position in the relation
   */
  static class Arg {
    Relation r;
    int rOccurrence;
    int argPos;
    private int hc;
    public Arg(Relation r, int rOccurrence, int argPos) {
      this.r = r;
      this.rOccurrence = rOccurrence;
      this.argPos = argPos;
      int rhc = r == null ? 0 : r.hashCode();
      this.hc = Hash.mix(rhc, rOccurrence, argPos);
    }
    public Arg(Ri rel, int argPos) {
      this.r = rel.r;
      this.rOccurrence = rel.rOccurrence;
      this.argPos = argPos;
    }
    @Override
    public int hashCode() {
      return hc;
    }
    @Override
    public boolean equals(Object other) {
      if (other instanceof Arg) {
        Arg a = (Arg) other;
        return argPos == a.argPos && rOccurrence == a.rOccurrence && r.equals(a.r);
      }
      return false;
    }
    @Override
    public String toString() {
      String rel = r == null ? "null" : r.getName();
//      return "(Arg " + argPos + " of " + rel + "(" + rOccurrence + "))";
      return "(Arg R=" + rel + " i=" + rOccurrence + " a=" + argPos + ")";
    }
  }

  /**
   * explicitly: (R,i) -> HypEdge
   * implicitly: (R,i,a) -> HypNode because you can look up HypNodes from a HypEdge
   */
  static class Bindings {
    private State s;
    private LinkedMap<Ri, HypEdge> ri2edge;
    private Counts<Relation> rCounts;

    public Bindings(State s) {
      this.s = s;
      this.ri2edge = new LinkedMap<>();
      this.rCounts = new Counts<>();
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("(Bindings ri2edge=");
      sb.append(ri2edge);
      sb.append(" rCounts=");
      sb.append(rCounts);
      sb.append(')');
      return sb.toString();
    }

    public int size() {
      int n = ri2edge.size();
      assert rCounts.getTotalCount() == n;
      return n;
    }

    public HypEdge lastAdded() {
      return ri2edge.get(ri2edge.lastKey());
    }

    public State getState() {
      return s;
    }

    public HypEdge get(Ri rel) {
      return ri2edge.get(rel);
    }

    public HypNode get(Arg arg) {
      HypEdge e = get(new Ri(arg.r, arg.rOccurrence));
      return e.getTail(arg.argPos);
    }

    public void add(HypEdge e) {
      Relation r = e.getRelation();
      int occurrence = rCounts.increment(r);
      Ri key = new Ri(r, occurrence);
      ri2edge.put(key, e);
    }

    public void remove(HypEdge e) {
      int n = ri2edge.size();
      HypEdge re = ri2edge.remove(n-1);
      assert re == e;
      rCounts.update(re.getRelation(), -1);
    }
  }
}
