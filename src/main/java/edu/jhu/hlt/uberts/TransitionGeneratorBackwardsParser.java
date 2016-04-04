package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.srl.FNParseToRelations;

/**
 * I think the proper way is to go fully declarative, remove all imperative
 * code from my model specification.
 *
 * {@link FNParseToRelations} currently shows a way to over-generate what is
 * needed, in the near future we can get by as long as it can output
 * "y srl4(t,f,k,s) ..." lines. The transition grammar will be used to derive
 * all of the nodes that could be used to prove that (backwards chaining).
 *
 * I'm assuming that {@link Uberts#readRelData(java.io.BufferedReader)} is also
 * doing the job of adding the data (creating the NodeTypes and Relations is
 * only really importance insofar as it helps accomplish the primary goal: add
 * the data).
 *
 * The purpose of this class is to parse a transition grammar so that it can
 * expand y edges into edges which are "good" w.r.t. a transition grammar.
 * I say "good" and not "necessary", since I'm going to take ALL edges which
 * could lie in a derivation of a final edge (there may be more than one).
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

  // e.g. srl2(t,s)
  static class Term {
    Relation rel;
    String[] argNames;
    public Term(Relation rel, String... argNames) {
      this.rel = rel;
      this.argNames = argNames;
      assert argNames.length == rel.getNumArgs();
    }
    @Override
    public String toString() {
//      return "<Term " + rel.getName() + " " + Arrays.toString(argNames) + ">";
      StringBuilder sb = new StringBuilder();
      sb.append(rel.getName());
      sb.append('(');
      sb.append(argNames[0]);
      for (int i = 1; i < argNames.length; i++) {
        sb.append(',');
        sb.append(argNames[i]);
      }
      sb.append(')');
      return sb.toString();
    }
  }

  // e.g. event2(t,f) & srl2(t,s) & role(f,k) => srl3(t,f,s,k)
  static class Rule {
    Term rhs;
    Term[] lhs;

    // keys are variable names in rhs (for EVERY variable, lhsBindings.size == rhs.argNames.length)
    // values lists of (termIndex,argIndex)
    // lhsBindings["t"] = [(0,0), (1,0)] # for event2(t,...) and srl2(t,...) respectively
//    Map<String, List<IntPair>> lhsBindings;

    // lhs2rhs[1][0] = 0, second occurrence of t in lhs => location of t in rhs
    int[][] lhs2rhs;  // [termIdx][argIdx] => location in rhs.args, or -1 if not in rhs.

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append(lhs[0].toString());
      for (int i = 1; i < lhs.length; i++) {
        sb.append(" & ");
        sb.append(lhs[i].toString());
      }
      sb.append(" => ");
      sb.append(rhs.toString());
      return sb.toString();
    }

    public Rule(List<Term> lhs, Term rhs) {
      this.rhs = rhs;
      this.lhs = new Term[lhs.size()];
      for (int i = 0; i < this.lhs.length; i++)
        this.lhs[i] = lhs.get(i);
      this.lhs2rhs = new int[lhs.size()][];
      for (int i = 0; i < lhs2rhs.length; i++) {
        int lhsN = this.lhs[i].argNames.length;
        lhs2rhs[i] = new int[lhsN];
        for (int j = 0; j < lhsN; j++) {
          String varName = this.lhs[i].argNames[j];
          lhs2rhs[i][j] = indexOf(varName, rhs.argNames);
        }
      }
    }

    public static int indexOf(String needle, String[] haystack) {
      for (int i = 0; i < haystack.length; i++)
        if (needle.equals(haystack[i]))
          return i;
      return -1;
    }

    public List<Term> getAllTerms() {
      List<Term> t = new ArrayList<>();
      for (Term tt : lhs)
        t.add(tt);
      t.add(rhs);
      return t;
    }
  }


  private Map<Relation, List<Rule>> howToMake = new HashMap<>();
  private boolean verbose = false;

  public void add(Rule r) {
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

  /** Assume that every term in wayToDerive.lhs is true */
  private void expand(HypEdge fact, Rule wayToDerive, List<HypEdge> addTo) {
    if (verbose)
      Log.info("back-chaining from " + fact);
    assert wayToDerive.rhs.rel == fact.getRelation();
    assert wayToDerive.rhs.argNames.length == fact.getNumTails();
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

  public static void main(String[] args) {
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
}
