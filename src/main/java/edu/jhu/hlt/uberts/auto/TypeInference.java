package edu.jhu.hlt.uberts.auto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.prim.tuple.Pair;

/**
 * Assuming you have added some "def relationName <nodeTypeForArg0> ..."
 * relation definitions to {@link Uberts}, this class allows you to add
 * transition grammar rules
 *   e.g. srl1'(s1,ss,se) & event1'(e1,ts,te) => srl2(s1,e1)
 * Which introduce new relations which are not defined/typed yet. This class
 * propagates known types to the new relations, adding NodeTypes and Relations
 * to Uberts as necessary. Handles Hobbs' prime notation fine.
 *
 * @see TransitionGeneratorBackwardsParser, which this replaces.
 *
 * @author travis
 */
public class TypeInference {

  // Graph structure
  private Map<Arg, List<Arg>> edges;  // bi-directional, based on name equality, no head nodes (defs or usages)
  private Map<String, Collection<Arg>> rel2HeadNodeUsages;

  // Type inference
  private Map<String, Set<Arg>> rel2UntypedArgs;
  private Map<String, Integer> rel2NumArgs;
  private Map<Arg, NodeType> typed;

  // Value inference
  private Map<String, Set<Arg>> rel2UnvaluedArgs;
  private Map<Arg, HypNode> values;  // null while not doing value propagation

  // Misc
  private List<Rule> rules;
  private Uberts u;
  public boolean debug = false;


  public TypeInference(Uberts u) {
    this.u = u;
    this.edges = new HashMap<>();
    this.rel2UntypedArgs = new HashMap<>();
    this.rel2HeadNodeUsages = new HashMap<>();
    this.rel2NumArgs = new HashMap<>();
    this.typed = new HashMap<>();
    this.rules = new ArrayList<>();
  }

  public List<HypEdge> expand(HypEdge fact) {
    // We re-set the inferred values for every fact
    this.values = new HashMap<>();
    this.rel2UnvaluedArgs = new HashMap<>();
    for (Rule r : rules) {
      for (Term t : r.lhs) {
        Set<Arg> unvalued = rel2UnvaluedArgs.get(t.relName);
        if (unvalued == null) {
          unvalued = new HashSet<>();
          rel2UnvaluedArgs.put(t.relName, unvalued);
        }
        int na = t.getNumArgs();
        for (int i = 0; i < na; i++)
          unvalued.add(new Arg(t.relName, i));
      }
    }

    List<HypEdge> all = new ArrayList<>();
    for (Rule r : rules)
      expand(fact, r, all);
    return all;
  }

  private Set<Pair<Arg, String>> alreadyWarnedAbout = new HashSet<>();
  private void expand(HypEdge fact, Rule r, List<HypEdge> addTo) {
    assert r.rhs.rel != null : "did you run type inference?";
    if (!r.rhs.relName.equals(fact.getRelation().getName()))
      return; // not applicable
    if (debug)
      Log.info("fact=" + fact + " rule=" + r);
    Relation rel = r.rhs.rel;
    assert rel == u.getEdgeType(r.rhs.relName);
    assert rel == fact.getRelation();

    // Propagate values
    int na = rel.getNumArgs();
    for (int i = 0; i < na; i++) {
      Arg known = new Arg(rel.getName(), i);
      HypNode val = fact.getTail(i);
      addValue(known, val, new HashSet<>());
    }

    // Collect the values into inferred facts
    int lhst = r.lhs.length;
    lhsTerm:
    for (int i = 0; i < lhst; i++) {
      Relation lrel = r.lhs[i].rel;
      assert lrel != null;
      int lrelNA = lrel.getNumArgs();
      HypNode[] tail = new HypNode[lrelNA];
      for (int j = 0; j < lrelNA; j++) {
        Arg q = new Arg(lrel.getName(), j);
        tail[j] = values.get(q);
        if (tail[j] == null) {
          if (alreadyWarnedAbout.add(new Pair<>(q, fact.getRelation().getName())))
            Log.warn("could not compute value for " + q + " based on " + fact + ", skipping");
          continue lhsTerm;
        }
      }
      HypEdge e = u.makeEdge(lrel, tail);
      if (debug)
        Log.info("assumed: " + e);
      addTo.add(e);
    }
  }

  // populates: this.rel2UntypedArgs
  // populates: this.rel2NumArgs
  private void helpAddRelationOnce(Term t) {
    int na = t.argNames.length;
    Integer na2 = rel2NumArgs.get(t.relName);
    if (na2 != null) {
      assert na2 == na;
    } else {
      // First time seeing this argument
      rel2NumArgs.put(t.relName, na);
      Set<Arg> untyped = new HashSet<>();
      for (int i = 0; i < na; i++) {
        Arg a = new Arg(t.relName, i);
        if (debug)
          Log.info("untyped: " + a);
        untyped.add(a);
      }
      Object old = rel2UntypedArgs.put(t.relName, untyped);
      assert old == null;
    }
  }

  // populates: this.typed
  private void helpAddTypesFromPredefinedRelations(Term t) {
    assert rel2NumArgs.containsKey(t.relName);
    Relation rel = u.getEdgeType(t.relName, true);
    if (rel != null) {
      for (int i = 0; i < t.argNames.length; i++) {
        Arg a = new Arg(t.relName, i);
        if (debug)
          Log.info("predefined: " + a);
        NodeType nt = rel.getTypeForArg(i);
        addType(a, nt, new HashSet<>());
//        Object old = typed.put(a, nt);
//        assert old == null || old == nt;
//
//        Collection<Arg> untyped = rel2UntypedArgs.get(t.relName);
//        if (untyped != null) {
//          untyped.remove(a);
//          if (untyped.isEmpty())
//            rel2UntypedArgs.remove(t.relName);
//        }
      }
    }
  }

  private void helpAddHeadNodeUsage(Term definesHeadNode, Arg usesHeadNode) {
    if (debug)
      Log.info(usesHeadNode + " uses head node of " + definesHeadNode.relName);
    Collection<Arg> l = rel2HeadNodeUsages.get(definesHeadNode.relName);
    if (l == null) {
      l = new ArrayList<>();
      rel2HeadNodeUsages.put(definesHeadNode.relName, l);
    }
    l.add(usesHeadNode);
  }

  private void helpAddNameEqEdge(Arg a, Arg b) {
    if (debug)
      Log.info("nameEq: " + a + "\t" + b);
    List<Arg> other = edges.get(a);
    if (other == null) {
      other = new ArrayList<>();
      edges.put(a, other);
    }
    other.add(b);
  }

  public void add(Rule untypedRule) {
    rules.add(untypedRule);

    // Find all pairs of Args which have the SAME NAME and aren't head/witness/fact nodes
    // populates: this.edges
    List<Term> terms = untypedRule.getAllTerms();
    int n = terms.size();
    for (int i = 0; i < n-1; i++) {
      for (int j = i+1; j < n; j++) {
        Term ti = terms.get(i);
        Term tj = terms.get(j);
        // Hash-join on names
        Map<String, Integer> n2k = new HashMap<>();
        for (int k = 0; k < ti.argNames.length; k++)
          n2k.put(ti.argNames[k], k);
        for (int k = 0; k < tj.argNames.length; k++) {
          Integer kk = n2k.get(tj.argNames[k]);
          if (kk != null) {
            // Common arg!
            Arg a = new Arg(ti.relName, kk);
            Arg b = new Arg(tj.relName, k);
            helpAddNameEqEdge(a, b);
            helpAddNameEqEdge(b, a);
          }
        }
      }
    }

    // populates: this.rel2UntypedArgs
    // populates: this.rel2NumArgs
    for (Term t : terms)
      helpAddRelationOnce(t);

    // populates: this.rel2HeadNodeUsages
    assert untypedRule.rhs.factArgName == null : "RHS nodes may not use fact variables!";
    for (int i = 0; i < untypedRule.lhs.length; i++) {
      Term lhs = untypedRule.lhs[i];
      if (lhs.factArgName != null) {
        // Find a use of this fact variable somewhere else in the rule
        for (Term t : terms) {
          if (t == lhs)
            continue;
          for (int j = 0; j < t.argNames.length; j++) {
            if (lhs.factArgName.equals(t.argNames[j])) {
              Arg use = new Arg(t.relName, j);
              helpAddHeadNodeUsage(lhs, use);
            }
          }
        }
      }
    }

    // populates: this.typed
    for (Term t : terms)
      helpAddTypesFromPredefinedRelations(t);
  }

  /**
   * Returns a list of typed rules.
   */
  public List<Rule> runTypeInference() {
    Log.info("Running type inference, typed.size=" + typed.size());
    List<Arg> roots = new ArrayList<>(typed.keySet());
    for (Arg a : roots)
      addType(a, typed.get(a), new HashSet<>());

    for (Rule r : rules) {
      for (Term t : r.getAllTerms()) {
        if (t.rel == null)
          t.rel = u.getEdgeType(t.relName);
        assert t.allArgsAreTyped();
        if (debug) {
          for (int i = 0; i < t.argNames.length; i++)
            Log.info("type(" + new Arg(t.relName, i) + ")=" + t.getArgType(i));
        }
      }
    }
    return rules;
  }

  // Should mimic addType
  private void addValue(Arg a, HypNode value, HashSet<Arg> visited) {

    // Store the value
    Object old = values.put(a, value);
    assert old == null || old == value;

    if (!visited.add(a)) {
      if (debug)
        Log.info("been here before: " + a);
      return;
    }

    if (debug)
      Log.info("adding: " + a + " := " + value);

    // See if we're done typing this Arg's Relation
    Set<Arg> uv = rel2UnvaluedArgs.get(a.relation);
    if (uv != null) {
      uv.remove(a);
      if (uv.isEmpty()) {
        rel2UnvaluedArgs.remove(a.relation);

        if (debug)
          Log.info("no more unvalued args for " + a.relation);

        // Build the fact
        Relation rel = u.getEdgeType(a.relation);
        int na = rel.getNumArgs();
        assert na == rel2NumArgs.get(a.relation);
        HypNode[] tail = new HypNode[na];
        for (int i = 0; i < na; i++) {
          Arg key = new Arg(a.relation, i);
          tail[i] = values.get(key);
          assert tail[i] != null;
        }
        HypEdge e = u.makeEdge(rel, tail);

        // Store the head value
        Arg head = new Arg(a.relation, Arg.WITNESS_ARGPOS);
        old = values.put(head, e.getHead());
        assert old == null || old == e.getHead();

        // Find all uses of this head value and propagate!
        Collection<Arg> boundToHead = rel2HeadNodeUsages.get(a.relation);
        if (boundToHead != null) {
          for (Arg b : boundToHead) {
            if (debug)
              Log.info(b + " is bound to head node of " + a.relation);
            addValue(b, e.getHead(), visited);
          }
        }
      }
    }

    // Propagate this value to other Args which were bound in the same Rule
    // with the same name.
    Collection<Arg> sameName = edges.get(a);
    if (sameName != null)
      for (Arg s : sameName)
        addValue(s, value, visited);
  }

  private void addType(Arg a, NodeType t, HashSet<Arg> visited) {
    if (t == null || visited == null)
      throw new IllegalArgumentException();

    // Store the type
    Object old = typed.put(a, t);
    assert old == null || old == t;

    if (!visited.add(a)) {
      if (debug)
        Log.info("been here before: " + a);
      return;
    }

    if (debug)
      Log.info("adding: " + a + " :: " + t);

    // See if we're done typing this Arg's Relation
    Set<Arg> ut = rel2UntypedArgs.get(a.relation);
    if (ut != null) {
      ut.remove(a);
      if (ut.isEmpty()) {
        if (debug)
          Log.info("no more untyped args for " + a.relation);
        // This was the last Arg we needed to type
        rel2UntypedArgs.remove(a.relation);

        // Build Relation
        Relation rel = u.getEdgeType(a.relation, true);
        if (rel == null) {
          int na = rel2NumArgs.get(a.relation);
          NodeType[] types = new NodeType[na];
          for (int i = 0; i < na; i++)
            types[i] = typed.get(new Arg(a.relation, i));
          rel = new Relation(a.relation, types);
          if (debug)
            Log.info("new relation: " + rel);
          u.addEdgeType(rel);
        }

        // Store head type
        Arg head = new Arg(a.relation, Arg.WITNESS_ARGPOS);
        NodeType headType = u.getWitnessNodeType(rel);
        old = typed.put(head, headType);
        assert old == null;
      }
    }

    // Propagate this type to other Args which were bound in the same Rule
    // with the same name.
    Collection<Arg> sameName = edges.get(a);
    if (sameName != null)
      for (Arg s : sameName)
        addType(s, t, visited);

    // If we known the Relation, find usages of this head type to propagate to
    Relation rel = u.getEdgeType(a.relation, true);
    if (rel != null) {
      NodeType headType = u.getWitnessNodeType(rel);
      Collection<Arg> boundToHead = rel2HeadNodeUsages.get(a.relation);
      if (boundToHead != null) {
        for (Arg b : boundToHead) {
          if (debug)
            Log.info(b + " is bound to head node of " + a.relation);
          addType(b, headType, visited);
        }
      }
    }
  }
}
