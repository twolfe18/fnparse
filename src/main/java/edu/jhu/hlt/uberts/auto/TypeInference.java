package edu.jhu.hlt.uberts.auto;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeStore;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;
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
 * {@see TransitionGeneratorBackwardsParser}, which this replaces.
 *
 * @author travis
 */
public class TypeInference {

  /**
   * Generates intermediate facts w.r.t. some labels and a grammar. Due to the
   * interplay between this code and {@link Uberts}'s {@link NodeStore}, this
   * can't easily be used as a mapper function over an iterator, like was tried
   * previously. Replacement for {@link TransitionGeneratorBackwardsParser.Iter}.
   */
  public class Expander {
    public Counts<String> counts;
    public Counts<String> countsByRelation;
    public Set<String> relationsToIgnore;
    private TimeMarker tm = new TimeMarker();

    // Anything Uberts-based which is not pure IO (e.g. training/inference) will
    // break if this is false. True requires a lot of memory.
    public boolean lookupHypNodes = true;

    public boolean onlyReadXYFacts = true;

    public Expander() {
      this(null);
    }
    public Expander(Collection<String> relationsToIgnore) {
      this.counts = new Counts<>();
      this.countsByRelation = new Counts<>();
      this.relationsToIgnore = new HashSet<>();
      if (relationsToIgnore != null)
        this.relationsToIgnore.addAll(relationsToIgnore);
    }

    public void expand(RelDoc d) {
      assert d.facts.isEmpty();
      Set<HashableHypEdge> seen = new HashSet<>();

      // Move
      for (RelLine l : d.items) {
        HypEdge.WithProps e = u.makeEdge(l, lookupHypNodes);
        if (onlyReadXYFacts && !(e.hasProperty(HypEdge.IS_X) || e.hasProperty(HypEdge.IS_Y)))
          continue;
        if (seen.add(new HashableHypEdge(e)))
          d.facts.add(e);
      }
      d.items.clear();
      counts.update("input-facts", d.facts.size());

      // Expand
      for (int i = 0; i < d.facts.size(); i++) {
        HypEdge.WithProps fact = d.facts.get(i);
        for (HypEdge.WithProps e : TypeInference.this.expand(fact)) {
          String rel = e.getRelation().getName();
          if (relationsToIgnore.contains(rel)) {
            counts.increment("facts-skipped");
            countsByRelation.increment("skipped-" + rel);
            continue;
          }
          if (seen.add(new HashableHypEdge(e))) {
            d.facts.add(e);
            counts.increment("facts-derived");
          }
        }
      }
      counts.update("facts-output", d.facts.size());
      if (tm.enoughTimePassed(60)) {
        Log.info("[TypeInference.Expander] " + counts + " in "
            + tm.secondsSinceFirstMark() + " seconds");
      }
    }
  }

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
  private Set<String> seenRHS;
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
    this.values = new HashMap<>();
    this.rel2UnvaluedArgs = new HashMap<>();
    this.seenRHS = new HashSet<>();
  }

  public Uberts getUberts() {
    return u;
  }

  public void clearValues() {
    if (debug)
      Log.info("values.size=" + values.size() + " rel2UnvaluedArgs=" + rel2UnvaluedArgs.size());
    this.values.clear();
    this.rel2UnvaluedArgs.clear();
  }

  public List<HypEdge.WithProps> expand(HypEdge.WithProps fact) {
    // We re-set the inferred values for every fact
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

    List<HypEdge.WithProps> all = new ArrayList<>();
    for (Rule r : rules)
      expand(fact, r, all);

    clearValues();
    return all;
  }

  private Set<Pair<Arg, String>> alreadyWarnedAbout = new HashSet<>();

  /**
   * If the RHS of the given rule matches the given fact's Relation, use the
   * fact's values and the rule's pairs of co-bound arguments to propagate from
   * RHS to LHS, creating new facts.
   */
  private void expand(HypEdge.WithProps fact, Rule r, List<HypEdge.WithProps> addTo) {
    assert r.rhs.rel != null : "did you run type inference?";
    if (!r.rhs.relName.equals(fact.getRelation().getName()))
      return; // not applicable
    if (debug)// || r.rhs.relName.equals("event2"))
      Log.info("fact=" + fact + " rule=" + r);
    Relation rel = r.rhs.rel;
    assert rel == u.getEdgeType(r.rhs.relName);
    assert rel == fact.getRelation();

    // Propagate values
    int na = rel.getNumArgs();
    for (int i = 0; i < na; i++) {
      Arg known = new Arg(rel.getName(), i);
      HypNode val = fact.getTail(i);
      propagateValue(known, val, new HashSet<>());
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
            Log.warn("could not compute value for " + q + " based on " + fact + " in " + r + ", skipping");
          continue lhsTerm;
        }
      }
      // TODO I currently don't see any reason why isSchema should be true...
      boolean isSchema = false;
      HypEdge.WithProps e = u.makeEdge(isSchema, lrel, tail);
      e.setPropertyMask(fact.getProperties() | HypEdge.IS_DERIVED);

      if (debug) {// || e.getRelation().getName().startsWith("event")) {
        HashableHypEdge hhe = new HashableHypEdge(e);
        System.out.println("[TypeInference] adding: " + hhe.hashDesc());
      }

      addTo.add(e);
    }
  }

  // populates: this.rel2UntypedArgs
  // populates: this.rel2NumArgs
  private void helpAddRelationOnce(Term t) {
    int na = t.argNames.length;
    Integer na2 = rel2NumArgs.get(t.relName);
    if (na2 != null) {
      assert na2 == na : t.relName + " used to have " + na2 + " args, now has " + na;
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
        propagateType(a, nt, new HashSet<>());
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

    // Check that all RHS vars are bound on the LHS
    untypedRule.buildRhsArg2LhsBindings();

    if (!seenRHS.add(untypedRule.rhs.relName)) {
      // TODO This is going to cause problems:
      // I currently do:
      // a & b => c
      // b & a => c
//      throw new IllegalStateException("you may only have one production rule"
//          + " for each relation type: " + untypedRule);
      Log.warn("duplicate RHS definition: " + untypedRule);
      Log.warn("this is only temporarily allowed, and for cases where the"
          + " LHSs are all the same but in different orders!");
    }
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
      propagateType(a, typed.get(a), new HashSet<>());

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
  private void propagateValue(Arg a, HypNode value, HashSet<Arg> visited) {
    // Store the value
    Object old = values.put(a, value);
    assert old == null || old == value
        : a + " had value " + old + ", but you just offered " + value;

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
        // TODO I currently don't see any reason why isSchema should be true...
        boolean isSchema = false;
        HypEdge e = u.makeEdge(isSchema, rel, tail);

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
            propagateValue(b, e.getHead(), visited);
          }
        }
      }
    }

    // Propagate this value to other Args which were bound in the same Rule
    // with the same name.
    Collection<Arg> sameName = edges.get(a);
    if (sameName != null) {
      for (Arg s : sameName) {
        if (debug)
          Log.info(s + " has the same name as " + a + ", propagating value: " + value);
        propagateValue(s, value, visited);
      }
    }
  }

  private void propagateType(Arg a, NodeType t, HashSet<Arg> visited) {
    if (t == null || visited == null)
      throw new IllegalArgumentException();

    // Store the type
    Object old = typed.put(a, t);
    assert old == null || old == t
        : a + " use to have type " + old + " but it was just set to " + t;

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
        propagateType(s, t, visited);

    // If we known the Relation, find usages of this head type to propagate to
    Relation rel = u.getEdgeType(a.relation, true);
    if (rel != null) {
      NodeType headType = u.getWitnessNodeType(rel);
      Collection<Arg> boundToHead = rel2HeadNodeUsages.get(a.relation);
      if (boundToHead != null) {
        for (Arg b : boundToHead) {
          if (debug)
            Log.info(b + " is bound to head node of " + a.relation);
          propagateType(b, headType, visited);
        }
      }
    }
  }

  /**
   * Writes a graph representation of a transition system.
   * Run type inference first so that the types are known before calling.
   */
  public void toDOT(BufferedWriter w, boolean argMode) throws IOException {
    // DOT does not allow "-" in a node id, which is what Arg.toString uses.
    Function<Arg, String> a2s = a -> {
      String s = a.relation + ".arg" + a.argPos;
      return s.replaceAll("-", "_").replace(".", "_");
    };

    w.write("digraph transitionGrammar {");
    w.newLine();
    w.write("rankdir=LR;");
    w.newLine();

    if (argMode) {
      // First output the graph of Args
      w.write("subgraph typeConstraints {");
      w.newLine();
      w.write("edge [dir=none,color=purple];");
      w.newLine();
      Set<Pair<String, String>> seenSameTypeEdges = new HashSet<>();
      for (Entry<Arg, List<Arg>> a : edges.entrySet()) {
        String source = a2s.apply(a.getKey());
        for (Arg sinkNode : a.getValue()) {
          String sink = a2s.apply(sinkNode);
          Pair<String, String> k = new Pair<>(source, sink);
          if (!seenSameTypeEdges.contains(k)) {
            seenSameTypeEdges.add(k);
            seenSameTypeEdges.add(new Pair<>(sink, source));
            w.write(source + " -> " + sink);
            w.write(" [label=sameVarName];");
            w.newLine();
          }
        }

        NodeType t = typed.get(a.getKey());
        w.write(t.getName().replaceAll("-", "_") + " -> " + source + " [label=typeOf,color=orange]");
        w.newLine();
      }
      w.write("}");
      w.newLine();
    }

    // Relations and their args
    Set<String> seenNT = new HashSet<>();
    Set<String> seenRel = new HashSet<>();
    for (Relation r : u.getAllEdgeTypes()) {
      // Relation
      String rs = r.getName().replaceAll("-", "_");
      if (seenRel.add(rs)) {
        w.write(rs + " [color=red];");
        w.newLine();
      }
      String source = r.getName().replaceAll("-", "_");
      // NodeType (head)
      String nts;
//      nts = Uberts.getWitnessNodeTypeName(r).replaceAll("-", "_")
//          .replace("witness", "wtn");
//      if (seenNT.add(nts)) {
//        w.write(nts + " [color=green];");
//        w.newLine();
//      }
      for (int i = 0; i < r.getNumArgs(); i++) {
        // NodeType
        nts = r.getTypeForArg(i).getName().replaceAll("-", "_");
        if (seenNT.add(nts)) {
          w.write(nts + " [color=green];");
          w.newLine();
        }
        // Args
        String sink = r.getTypeForArg(i).getName().replace("-", "_");
        String e = source + " -> " + sink + " [label=a" + i + "];";
        w.write(e);
        w.newLine();
      }
      // Head
      String e = Uberts.getWitnessNodeTypeName(r).replaceAll("-", "_")
          .replace("witness", "wtn")
          + " -> " + r.getName().replaceAll("-", "_") + " [label=head];";
      w.write(e);
      w.newLine();
    }

    // Rules: RHS -> {LHS}
    for (Rule typedRule : rules) {
      for (Term lhs : typedRule.lhs) {
        String source = typedRule.rhs.relName.replaceAll("-", "_");
        String sink = lhs.relName.replaceAll("-", "_");
        w.write(source + " -> " + sink + " [label=rule,color=blue];");
        w.newLine();
      }
    }

    w.write("}");
    w.newLine();
  }

  /** Builds a DOT graph for visualizing the transition rules */
  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      System.err.println("please provide:");
      System.err.println("1) a relation definitions file, e.g. data/srl-reldata/propbank/relations.def");
      System.err.println("2) a transition grammar file, e.g. data/srl-reldata/srl-grammar.hobbs.trans");
      System.err.println("3) a file to output DOT graph to");
      return;
    }
    File defs = new File(args[0]);
    File grammar = new File(args[1]);
    File output = new File(args[2]);

    Uberts u = new Uberts(new Random(9001));
    u.readRelData(defs);

    TypeInference ti = new TypeInference(u);
    for (Rule untypedRule : Rule.parseRules(grammar, null))
      ti.add(untypedRule);
    ti.runTypeInference();

    Log.info("writing graph to: " + output.getPath());
    boolean argMode = true;
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      ti.toDOT(w, argMode);
    }
    Log.info("done");
  }

}
