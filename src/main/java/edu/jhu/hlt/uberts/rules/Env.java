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

public class Env {

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
      return b.getState().match2(r).toList();
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

  public static class Trie3 {
    // FOR DEBUGGING, null means don't track counts
    public static final Counts<String> EVENT_COUNTS = new Counts<>();
    
    private Trie3 root;
    private Trie3 parent; // allows you to compute i from just R

    private Term term;            // Provides variable names
    private Relation rel;         // R (view on term)
    private int relOccurrence;    // i (view on (term, parent))

    // How to get to the next Trie3 node
    // Edge3 can be though of as constraints to check while binding the next fact.
    List<Edge3> whatToBind;
    // Concrete example why this map should be separate from Edge3s:
    // Rule1 = "foo(x,y) & bar(x,y) => ..."
    // Rule2 = "foo(x,z) & bar(y,z) => ..."
    // Rule1 will require an Edge3 which checks equality for both {x,y}
    // Rule2 will only require an Edge3 to check {z} equality.
    // Both Rule1 and Rule2 will end up in a state where we have just bound a bar fact/HypEdge.
    Map<Relation, Trie3> children;

    // Maps (R,i,a) <-> (ruleIdx,termIdx,argIdx)
    // Includes an (R,i,a) entry for every Trie3 node between this and ROOT.
    // ONLY NON-NULL AT ROOT. (Global mapping, not node-specific)
    private RiaRtaBijection varMapping;

    // If non-null, this is an accepting state, and this is the completed rule.
    private Rule isCompleted;

    public static Trie3 makeRoot() {
      return  new Trie3(null, -1, null, null);
    }

    public Trie3(Term t, int relOccurrence, Rule isCompleted, Trie3 parent) {
      this.term = t;
      if (t == null) {
        // ROOT
        this.varMapping = new RiaRtaBijection();
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
      this.isCompleted = isCompleted;
      cnt("newTrieNode");
    }

    private static void cnt(String msg) {
      if (EVENT_COUNTS != null)
        EVENT_COUNTS.increment(msg);
    }

    /**
     * Emit all proofs using facts in the state of the each of the formulas
     * contained in this trie.
     */
    public void match(State s, HypEdge lastFact, Consumer<Match> emit) {
      Bindings b = new Bindings(s);
      b.add(lastFact);
      Trie3 child = children.get(lastFact.getRelation());
      child.match(s, b, emit);
      cnt("match/root");
    }

    private void match(State s, Bindings b, Consumer<Match> emit) {
      assert b.get(new Ri(rel, relOccurrence)) != null
          : "should have bound " + rel.getName() + " by the time you reached this Trie3 node";

      cnt("match");
      if (isCompleted != null) {
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
     */
    class Match {
      private Bindings bindings;
      public Match(Bindings b) {
        this.bindings = b;
      }
      public HypNode getValue(int termIdx, int argIdx) {
        IntTrip rta = new IntTrip(isCompleted.index, termIdx, argIdx);
        Arg ria = root.varMapping.get(rta);
        return bindings.get(ria);
      }
      public HypEdge getValue(int termIdx) {
        IntPair rt = new IntPair(isCompleted.index, termIdx);
        Ri ri = root.varMapping.get(rt);
        return bindings.get(ri);
      }
      @Override
      public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("(Match ");
        sb.append(isCompleted.toString());
        for (int i = 0; i < isCompleted.lhs.length; i++) {
          sb.append(' ');
          sb.append(getValue(i));
        }
//        sb.append(" bindings=" + bindings);
        sb.append(')');
        return sb.toString();
      }
    }

    public void add(Rule r) {
      // At the root you should manually add an edge for every fact in the LHS.
      // After that, respect the order of the LHS.
      // NOTE: I considered keeping this logic in UbertsPipeline, but its probably
      // best to always be correct in Uberts.
//      add(r, new BitSet());
      assert term == null : "is this not root?";
      int n = r.lhs.length;
      BitSet bs = new BitSet(n);
      for (int i = 0; i < n; i++) {
        bs.set(i, true);
        Term l = r.lhs[i];
        Trie3 child = children.get(l.rel);
        if (child == null) {
          child = new Trie3(l, 0, r.lhs.length == 1 ? r : null, this);
          children.put(l.rel, child);
        }
        child.add(r, bs);
        bs.set(i, false);
      }
    }

    private void add(Rule r, BitSet lhsCovered) {
      if (r.index < 0)
        throw new IllegalArgumentException("must set Rule indices");

      // How do I know i? Which occurrence of R this is?
      // I could walk up the path, or I could pass down a map.
      // I think I'll walk up.
      int nextLhsTermIdx = chooseNextArgPos(r, lhsCovered);
      if (nextLhsTermIdx < 0) {
        // We're done
        assert lhsCovered.cardinality() == r.lhs.length;
        return;
      }
      assert !lhsCovered.get(nextLhsTermIdx);
      Term nextLhsTerm = r.lhs[nextLhsTermIdx];

      int nextLhsTermRelOccurrence = 0;
      for (Trie3 cur = this; cur != null; cur = cur.parent)
        if (nextLhsTerm.rel.equals(cur.rel))
          nextLhsTermRelOccurrence++;

      // Make child, will add later
      Trie3 c = children.get(nextLhsTerm.rel);
      if (c == null) {
        Rule isComplete = null;
        if (lhsCovered.cardinality()+1 == r.lhs.length)
          isComplete = r;
        c = new Trie3(nextLhsTerm, nextLhsTermRelOccurrence, isComplete, this);
        children.put(nextLhsTerm.rel, c);
      }

      // keys are (r,t,a) and values are (R,i,a) which are bound by parents
      // of this node.
      List<ArgEquality> eqConstraints = new ArrayList<>();
      for (int argIdx : nextLhsTerm.getArgIndices()) {
        // Two views on this variable
        Arg ria = new Arg(nextLhsTerm.rel, nextLhsTermRelOccurrence, argIdx);
        IntTrip rta = new IntTrip(r.index, nextLhsTermIdx, argIdx);

        // Update (R,i,a) <-> (r,t,a) bijection
        // TODO This is un-necessary, can have (R,i) <-> (r,t) bijection, promote to include a trivially (identity)
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
      c.add(r, lhsCovered);
      lhsCovered.set(nextLhsTermIdx, false);
    }

    /**
     * Returns the index of a LHS term which is not yet covered.
     * Rank based on (in order):
     * 1) whether this term has already bound variables
     * 2) how many un-bound terms could be bound by choosing this relation
     * 3) order of appearance as the rule was given (leftmost terms come first)
     */
    private int chooseNextArgPos(Rule r, BitSet lhsCovered) {
      Log.warn("TODO finish this implementation!");
      // Just choose the first for now
      int n = r.lhs.length;
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

  public static class RiaRtaBijection {
    private Map<Arg, IntTrip> ria2rta;
    private Map<IntTrip, Arg> rta2ria;
    private Map<IntPair, Ri> rt2ri;
    private Map<Ri, IntPair> ri2rt;
    public RiaRtaBijection() {
      ria2rta = new HashMap<>();
      rta2ria = new HashMap<>();
      rt2ri = new HashMap<>();
      ri2rt = new HashMap<>();
    }
    public void add(Arg ria, IntTrip rta) {
      Object old;
      old = ria2rta.put(ria, rta);
      if (old != null && !old.equals(rta))
        throw new RuntimeException("key=" + ria + " old=" + old + " new=" + rta);
      old = rta2ria.put(rta, ria);
      if (old != null && !old.equals(ria))
        throw new RuntimeException("key=" + ria + " old=" + old + " new=" + rta);
      Ri ri = new Ri(ria.r, ria.rOccurrence);
      IntPair rt = new IntPair(rta.first, rta.second);
      old = ri2rt.put(ri, rt);
      if (old != null && !old.equals(rt))
        throw new RuntimeException();
      old = rt2ri.put(rt, ri);
      if (old != null && !old.equals(ri))
        throw new RuntimeException();
    }
    public Arg get(IntTrip rta) {
      return rta2ria.get(rta);
    }
    public IntTrip get(Arg ria) {
      return ria2rta.get(ria);
    }
    public Ri get(IntPair rt) {
      return rt2ri.get(rt);
    }
    public IntPair get(Ri ri) {
      return ri2rt.get(ri);
    }
  }

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
   * implicitly: (R,i,a) -> HypNode
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
