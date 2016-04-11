package edu.jhu.hlt.uberts.auto;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;

/**
 * The purpose of this class is to parse a transition grammar so that it can
 * expand "necessary" y edges (the ones you get credit for in evaluation) into a
 * larger set which are "good" w.r.t. a transition grammar. An edge deemed
 * "good" is an edge used in the derivation of a "necessary" edge. There may be
 * ways of deriving "necessary" edges without adding "good" edges, since I'm
 * going to take ALL edges which could lie in a derivation of a final edge
 * (there may be more than one).
 * e.g. you could have the two rules in your grammar:
 *   srl3a(t,f,k) & srl2(t,s) => srl4(t,f,s,k)
 *   srl3b(t,f,s) & role(f,k) => srl4(t,f,s,k)
 * which would create two intermediate edges:
 *   srl3a(t,f,k)
 *   srl3b(t,f,s)
 * even though only one of them is needed to derive srl4(t,f,s,k)
 *
 * The input will be a grammar and a set of facts to expand.
 *
 * @author travis
 */
public class TransitionGeneratorBackwardsParser {
  public static boolean DEBUG = false;

  private Map<Relation, List<Rule>> howToMake = new HashMap<>();
  private boolean verbose = false;

  public void add(Rule r) {
    Log.info("adding " + r);
    Relation key = r.rhs.rel;
    List<Rule> vals = howToMake.get(key);
    if (vals == null) {
      vals = new ArrayList<>();
      howToMake.put(key, vals);
    }
    vals.add(r);
  }

  // e.g. fact = srl4(3-5, fn/foo, 5-9, fn/foo/bar)
  public List<HypEdge> expand(HypEdge fact) {
    List<Rule> rules = howToMake.get(fact.getRelation());
    if (rules == null)
      return Collections.emptyList();

    List<HypEdge> mustBeTrue = new ArrayList<>();
    for (Rule r : rules)
      expand(fact, r, mustBeTrue);
    return mustBeTrue;
  }

  private Set<Arg> alreadyWarnedAbout = new HashSet<>();
  /**
   * Assume that every term in wayToDerive.lhs must be true to prove fact, even
   * though there may be others paths. This is used to deriving training data
   * w.r.t. a transition grammar from training data that doesn't care about the
   * grammar.
   */
  private void expand(HypEdge fact, Rule wayToDerive, List<HypEdge> addTo) {
    if (verbose)
      Log.info("back-chaining from " + fact);
    assert wayToDerive.rhs.rel == fact.getRelation();
    assert wayToDerive.rhs.argNames.length == fact.getNumTails();
    lhsTerm:
    for (int lhsTermIdx = 0; lhsTermIdx < wayToDerive.lhs.length; lhsTermIdx++) {
      // We are using the assumption !lhsTerm => !rhs,
      // which is not really true, since there could be other ways to prove rhs.
      Term lhsTerm = wayToDerive.lhs[lhsTermIdx];
      if (verbose)
        Log.info("assuming this is true: " + lhsTerm);

      // Take values from fact, project them into wayToDerive.lhs's terms.
      HypNode[] assumedTrueArgs = new HypNode[lhsTerm.argNames.length];
      for (int argIdx = 0; argIdx < lhsTerm.argNames.length; argIdx++) {
        int rhsArgIdx = wayToDerive.lhs2rhs[lhsTermIdx][argIdx];
        if (rhsArgIdx < 0) {
          Arg a = new Arg(lhsTerm, argIdx);
          if (alreadyWarnedAbout.add(a)) {
            Log.warn(a + " is not used in RHS of " + wayToDerive
                + ", which means that we can't derive a complete "
                + lhsTerm.relName + " fact. Not showing any more warnings"
                + " for this argument.");
          }
          // TODO When Relations have a schema:boolean flag, check that here.
//          assert lhsTerm.rel instanceof HypEdge.Schema;
          continue lhsTerm;
        }
        Object value = fact.getTail(rhsArgIdx).getValue();
        NodeType nt = fact.getTail(rhsArgIdx).getNodeType();
        assumedTrueArgs[argIdx] = new HypNode(nt, value);
        if (verbose)
          Log.info("  arg[" + argIdx + "]=" + assumedTrueArgs[argIdx]);
      }
      HypEdge assumedTrue = new HypEdge(lhsTerm.rel, null, assumedTrueArgs);
      if (verbose)
        Log.info("  " + assumedTrue);
      addTo.add(assumedTrue);
    }
  }

  /**
   * A very simple run of the backwards generation.
   * This example DOES NOT include cases with LHS terms which contain variables
   * not bound on the RHS, e.g.
   *   event1(ts,te) & lemma(ts,lemma) & frameTriage(lemma,frame) => event2(ts,te,frame)
   * Where this method will necessarily fail.
   */
  public static void demo() {
    // e.g. event2(t,f) & srl2(t,s) & role(f,k) => srl3(t,f,s,k)
    NodeType spanNT = new NodeType("span");
    NodeType frameNT = new NodeType("frame");
    NodeType roleNT = new NodeType("role");
    Relation event2 = new Relation("event2", spanNT, frameNT);
    Relation srl2 = new Relation("srl2", spanNT, spanNT);
    Relation role = new Relation("role", frameNT, roleNT);
    Relation srl3 = new Relation("srl3", spanNT, frameNT, spanNT, roleNT);
    Term event2Term = new Term(event2, "t", "f");
    Term srl2Term = new Term(srl2, "t", "s");
    Term roleTerm = new Term(role, "f", "k");
    Term srl3Term = new Term(srl3, "t", "f", "s", "k");
    Rule r = new Rule(Arrays.asList(event2Term, srl2Term, roleTerm), srl3Term);
    System.out.println("rule: " + r);

    TransitionGeneratorBackwardsParser tgp = new TransitionGeneratorBackwardsParser();
    tgp.add(r);

    HypNode[] srl3FactArgs = new HypNode[4];
    srl3FactArgs[0] = new HypNode(spanNT, "3-5");           // t
    srl3FactArgs[1] = new HypNode(frameNT, "Commerce_buy"); // f
    srl3FactArgs[2] = new HypNode(spanNT, "0-3");           // s
    srl3FactArgs[3] = new HypNode(roleNT, "Buyer");         // k
    HypEdge srl3Fact = new HypEdge(srl3, null, srl3FactArgs);

    List<HypEdge> assumed = tgp.expand(srl3Fact);
    System.out.println("fact: " + srl3Fact);
    for (HypEdge e : assumed)
      System.out.println("gen: " + e);
  }

  /**
   * Node in the dependency graph over Relation argument NodeTypes.
   */
  static class Arg {
    Term term;
    int argPos;
    public Arg(Term t, int argPos) {
      this.term = t;
      this.argPos = argPos;
    }
    public String getArgName() {
      return term.argNames[argPos];
    }
    public boolean isDefined() {
      return term.getArgType(argPos) != null;
    }
    public NodeType getNodeType() {
      return term.getArgType(argPos);
    }
    public void setNodeType(NodeType nt) {
      term.setArgType(argPos, nt);
    }
    @Override
    public int hashCode() {
      return Hash.mix(term.relName.hashCode(), argPos);
    }
    @Override
    public boolean equals(Object other) {
      if (other instanceof Arg) {
        Arg a = (Arg) other;
        return term.relName.equals(a.term.relName) && argPos == a.argPos;
      }
      return false;
    }
    @Override
    public String toString() {
      if (isDefined())
        return "<Arg " + argPos + ":" + getNodeType() + " of " + term.relName + ">";
      return "<Arg " + argPos + " of " + term.relName + ">";
    }
  }

  static class TopoArgs {
    Map<Arg, Set<Arg>> outgoing = new HashMap<>();
    Map<String, Relation> relations = new HashMap<>();
    Map<Arg, NodeType> types = new HashMap<>();
    List<Rule> rules = new ArrayList<>();

    public void add(Rule r) {
      rules.add(r);
      int rhsNA = r.rhs.getNumArgs();
      for (int i = 0; i < rhsNA; i++) {
        Arg source = new Arg(r.rhs, i);
        for (int j = 0; j < r.lhs.length; j++) {
          int lhsTNA = r.lhs[j].getNumArgs();
          for (int k = 0; k < lhsTNA; k++) {
            Arg sink = new Arg(r.lhs[j], k);
            if (source.getArgName().equals(sink.getArgName())) {
              // Add to graph if they two vars share the same name
              Set<Arg> s = outgoing.get(source);
              if (s == null) {
                s = new HashSet<>();
                outgoing.put(source, s);
              }
              s.add(sink);
            }
          }
        }
      }
    }

    public Collection<Arg> getOutgoing(Arg source) {
      Collection<Arg> sinks = outgoing.get(source);
      if (sinks == null)
        sinks = Collections.emptyList();
      return sinks;
    }

    // Similar to Kahn's algorithm
    public void inferArgTyping() {
      Set<Arg> done = new HashSet<>();
      Set<Arg> defined = new HashSet<>();
      for (Arg a : outgoing.keySet()) {
        if (DEBUG)
          Log.info(a + " defined: " + a.isDefined());
        if (a.isDefined()) {
          defined.add(a);
          done.add(a);
        }
        Relation rel = a.term.rel;
        if (rel != null) {
          if (DEBUG)
            Log.info("adding relation: " + rel.getName());
          Relation old = relations.put(rel.getName(), rel);
          assert old == null || old == rel;
        }
      }
      if (DEBUG)
        Log.info("from the outset, " + defined.size() + " arguments are defined");
      while (defined.size() > 0) {
        Arg source = defined.iterator().next();
        if (DEBUG)
          Log.info("propagating types from: " + source);
        types.put(source, source.getNodeType());
        defined.remove(source);
        for (Arg sink : getOutgoing(source)) {
          if (sink.isDefined()) {
            if (DEBUG)
              System.out.println("\t" + sink + " already had type");
            assert source.getNodeType() == sink.getNodeType() : "double typed!";
          } else {
            if (DEBUG)
              System.out.println("\ttyping: " + sink);
            sink.setNodeType(source.getNodeType());

          }
          if (done.add(sink)) {
            if (DEBUG)
              Log.info("adding to queue for propagation: " + sink);
            defined.add(sink);
          }
        }
      }

      // Above guarantees that we typed every relation by name, but we may have
      // missed some instances due to false splitting. For example, if `event1`
      // is used in more than one Rule, each rule will reference its own Relation
      // instance since there is nothing collapsing them by name (usually Uberts
      // does this). Here we build this collapsed form with this.relation and
      // this.types, and all Arg types here, and as many Relation types as we
      // can (rest of args are done in addIntermediateRelations, which needs
      // to modify Uberts, this method doesn't, hence separate).
      for (Rule r : rules) {
        for (Term t : r.getAllTerms()) {

          for (int i = 0; i < t.argNames.length; i++) {
            if (t.getArgType(i) == null) {
              Arg key = new Arg(t, i);
              NodeType val = types.get(key);
              t.setArgType(i, val);
              if (DEBUG)
                Log.info("backup: " + val);
            }
          }

          // Some terms' relations may still be null since we haven't created
          // them yet. This is extracted into a separate method which takes
          // and modifies Uberts.
          if (t.rel == null)
            t.rel = relations.get(t.relName);

          if (!t.allArgsAreTyped()) {
            StringBuilder sb = new StringBuilder("Could not type [");
            for (int i = 0; i < t.argNames.length; i++) {
              if (i > 0)
                sb.append(", ");
              sb.append("arg " + i + " (named " + t.argNames[i] + ")");
            }
            sb.append("] of Relation " + t.relName);
            sb.append(". Make sure you loaded all defs for x, y, and schema data.");
            throw new RuntimeException(sb.toString());
          }
        }
      }
    }

    /**
     * For any relations which have been typed already (run inferArgTyping first),
     * but do not already existing in uberts, create them.
     */
    public void addIntermediateRelations(Uberts u) {
      for (Rule r : rules)
        for (Term t : r.getAllTerms())
          addRelationIfNeeded(t, u);
    }
    private void addRelationIfNeeded(Term t, Uberts u) {
      assert t.allArgsAreTyped();
      if (t.rel != null)
        return;
      t.rel = relations.get(t.relName);
      if (t.rel == null) {
        t.rel = new Relation(t.relName, t.getDerivedArgtTypes());
        u.addEdgeType(t.rel);
        Relation old = relations.put(t.rel.getName(), t.rel);
        assert old == null;
      }
    }

    public List<Rule> getRules() {
      return rules;
    }
  }

  public static void partiallyType(Rule r, Uberts u) {
    for (Term t : r.getAllTerms()) {
      Relation rel = u.getEdgeType(t.relName, true);
      if (rel != null) {
        t.rel = rel;
        if (DEBUG)
          Log.info("adding relation/types for " + t);
      }
    }
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    Uberts u = new Uberts(new Random(9001));

    // Define the succTok(i,j) relation and populate it
    Relation succTok = u.addSuccTok(1000);

    // Read in relation definitions for x and y data.
    // This will contain the definitions for final/output variables like
    // srl4(ts,te,f,ss,se,k), but DOES NOT NEED to have definitions for
    // intermediate relations like srl2(event1head,srl1head) which are inferred
    // from the grammar (next step).
    for (String defFileName : config.getString("defs").split(","))
      u.readRelData(new File(defFileName));
    if (DEBUG)
      Log.info("defined: " + u.getAllEdgeTypes());

    // Read in the grammar, create a dependency graph over variables in the
    // rules, toposort it, then use that to type every argument, and then every
    // relation.
    TransitionGeneratorBackwardsParser tgp = new TransitionGeneratorBackwardsParser();
    TopoArgs ta = new TopoArgs();
    File grammarFile = config.getExistingFile("grammar");
    for (Rule untypedRule : Rule.parseRules(grammarFile, null)) {
      partiallyType(untypedRule, u);
      ta.add(untypedRule);
    }
    Log.info("inferring types...");
    ta.inferArgTyping();
    Log.info("adding relations...");
    ta.addIntermediateRelations(u);
    for (Rule typedRule : ta.getRules()) {
      for (Term t : typedRule.getAllTerms()) {
        assert t.rel != null;
        assert t.allArgsAreTyped();
      }
      tgp.add(typedRule);
    }

    // Output the given x and y data PLUS the inferred intermediate labels.
    // TODO Right now this is going to file, but this could easily be routed
    // as input to uberts. Could also make an Iterator<RelDoc> class.
    TimeMarker tm = new TimeMarker();
    Counts<String> counts = new Counts<>();
    File outfile = config.getFile("output");
    File multiRefVals = config.getExistingFile("instances");
    boolean includeProvidence = true;
    Log.info("writing expanded facts to " + outfile.getPath());
    boolean lookupHypNodes = false;
    boolean dedupInputLines = true;
//    tgp.verbose = true;
    try (RelationFileIterator itr = new RelationFileIterator(multiRefVals, includeProvidence);
        ManyDocRelationFileIterator m = new ManyDocRelationFileIterator(itr, dedupInputLines);
        BufferedWriter w = FileUtil.getWriter(outfile)) {
      while (m.hasNext()) {
        RelDoc d = m.next();
        w.write(d.def.toLine());
        w.newLine();
        counts.increment("docs");

        // Expand every fact (recursively)
        // NOTE: Ensure that you call size() even loop iteration!
        counts.update("facts-input", d.items.size());
        for (int i = 0; i < d.items.size(); i++) {
          RelLine rel = d.items.get(i);
          HypEdge fact = u.makeEdge(rel, lookupHypNodes);
          for (HypEdge assume : tgp.expand(fact)) {
            if (assume.getRelation() == succTok)
              continue;
            counts.increment("facts-derived");
            d.items.add(assume.getRelLine("y", "derived"));
          }
        }

        // Write out (uniq) expanded facts
        Set<String> uniqKeys = new HashSet<>();
        Function<RelLine, String> keyFunc = rl -> StringUtils.join("\t", rl.tokens);
        for (RelLine line : d.items) {
          if (uniqKeys.add(keyFunc.apply(line))) {
            w.write(line.toLine());
            w.newLine();
            counts.increment("facts-written");
          }
        }

        if (tm.enoughTimePassed(15)) {
          w.flush();
          Log.info("in " + tm.secondsSinceFirstMark() + " seconds: " + counts);
        }
      }
    }

    Log.info("done, " + counts);
  }
}
