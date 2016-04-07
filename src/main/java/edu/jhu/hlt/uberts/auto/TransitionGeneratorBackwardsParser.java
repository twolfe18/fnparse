package edu.jhu.hlt.uberts.auto;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
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

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    Uberts u = new Uberts(new Random(9001));

    // TODO
    u.addSuccTok(1000);

    File defFile = config.getExistingFile("defs");
    u.readRelData(defFile);

    TransitionGeneratorBackwardsParser tgp = new TransitionGeneratorBackwardsParser();
    File grammarFile = config.getExistingFile("grammar");
    for (Rule r : Rule.parseRules(grammarFile, u))
      tgp.add(r);

    TimeMarker tm = new TimeMarker();
    Counts<String> counts = new Counts<>();

    File outfile = config.getFile("output");
    File multiRefVals = config.getExistingFile("instances");
    try (RelationFileIterator itr = new RelationFileIterator(multiRefVals);
        ManyDocRelationFileIterator m = new ManyDocRelationFileIterator(itr);
        BufferedWriter w = FileUtil.getWriter(outfile)) {
      while (m.hasNext()) {
        RelDoc d = m.next();
        counts.increment("docs");

        // Expand every fact (recursively)
        // NOTE: Ensure that you call size() even loop iteration!
        for (int i = 0; i < d.items.size(); i++) {
          counts.increment("facts-input");
          RelLine rel = d.items.get(i);
          HypEdge fact = u.makeEdge(rel);
          for (HypEdge assume : tgp.expand(fact)) {
            counts.increment("facts-derived");
            d.items.add(assume.getRelLine("y", "derived"));
          }
        }

        // Write out expanded facts
        for (RelLine line : d.items) {
          w.write(line.toLine());
          w.newLine();
          counts.increment("facts-written");
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
