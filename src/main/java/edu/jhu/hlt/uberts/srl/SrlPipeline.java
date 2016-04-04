package edu.jhu.hlt.uberts.srl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.HNode;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.TNode;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Rule;
import edu.jhu.hlt.uberts.auto.Term;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;

/**
 * Just get SRL working end-to-end.
 *
 * @author travis
 */
public class SrlPipeline {

  private Uberts u;
  private List<Rule> rules;

  public SrlPipeline(Uberts u, File defsFile, File valuesFile) throws IOException {
    this.u = u;
    rules = new ArrayList<>();

    // succTok(i,j)
    NodeType tokenIndex = u.lookupNodeType("tokenIndex", true);
    Relation succTok = u.addEdgeType(new Relation("succTok", tokenIndex, tokenIndex));
    HypNode prev = u.lookupNode(tokenIndex, String.valueOf(-1), true);
    for (int i = 0; i < 100; i++) {   // TODO figure out a better way to handle this...
      HypNode cur = u.lookupNode(tokenIndex, String.valueOf(i), true);
      u.addEdgeToState(u.makeEdge(succTok, prev, cur));
      prev = cur;
    }

    HypNode dbg = u.lookupNode(tokenIndex, "4", false);
    Log.info("A: neighbors(" + dbg + ")=" + u.getState().neighbors(new HNode(dbg)));

    // Read in the defs file which defines (and creates w.r.t. Uberts) the
    // relations which will appear in the values file.
    Log.info("reading in relation/node type definitions...");
    try (BufferedReader r = FileUtil.getReader(defsFile)) {
      u.readRelData(r);
    }
    System.out.println();


    // TODO: Relations which need to be populated
    // frameTriage2(lemma,frame)
    NodeType lemma = u.lookupNodeType("lemma", false);
    NodeType frame = u.lookupNodeType("frame", false);
    Relation frameTriage2 = u.addEdgeType(new Relation("frameTriage2", lemma, frame));
    // role(frame,role)
    NodeType roleNT = u.lookupNodeType("roleLabel", false);
    Relation role = u.addEdgeType(new Relation("role", frame, roleNT));


    // All single-word targets are possible
    addRule("pos2(i,t) & succTok(i,j) => event1(i,j)");

    // Build a table of (lemma,frame) for triage
    addRule("event1(i,j) & lemma2(i,l) & frameTriage2(l,f) => event2(i,j,f)");

    // Args can be any constituent in the stanford parse
    addRule("csyn3-stanford(i,j,lhs) => srl1(i,j)");
    addRule("srl1(i,j) & event1(a,b) => srl2(a,b,i,j)");
    addRule("srl2(a,b,i,j) & event2(a,b,f) & role(f,k) => srl3(a,b,f,i,j,k)");


    // Read in the x,y data
    Log.info("reading in x,y data...");
    try (BufferedReader r = FileUtil.getReader(valuesFile)) {
      u.readRelData(r);
    }
    System.out.println();

    Log.info("B: neighbors(" + dbg + ")=" + u.getState().neighbors(new HNode(dbg)));
  }

  private void addRule(String rule) {
    Log.info("adding " + rule);

    // Parse and save
    Rule r = parseRule(rule, u);
    rules.add(r);

    // Add to Uberts as a TransitionGenerator
    TransitionGeneratorForwardsParser tgfp = new TransitionGeneratorForwardsParser();
    Pair<List<TKey>, TransitionGenerator> tg = tgfp.parse2(r, u);
    u.addTransitionGenerator(tg.get1(), tg.get2());

    System.out.println();
  }

  public static Rule parseRule(String rule, Uberts u) {
    String[] lr = rule.split("=>");
    assert lr.length == 2;
    String lhs = lr[0].trim();
    String[] lhsTermStrs = lhs.split("&");
    Term[] lhsTerms = new Term[lhsTermStrs.length];
    for (int i = 0; i < lhsTerms.length; i++)
      lhsTerms[i] = parseTerm(lhsTermStrs[i].trim(), u);
    String rhs = lr[1].trim();
    Term rhsTerm = parseTerm(rhs, u);
    return new Rule(lhsTerms, rhsTerm);
  }

  public static Term parseTerm(String term, Uberts u) {
    int lrb = term.indexOf('(');
    int rrb = term.indexOf(')');
    assert lrb > 0 && rrb == term.length()-1;
    String relName = term.substring(0, lrb);
    Relation rel = u.getEdgeType(relName);
    String args = term.substring(lrb + 1, rrb);
    String[] argNames = args.split(",");
    for (int i = 0; i < argNames.length; i++)
      argNames[i] = argNames[i].trim();
    return new Term(rel, argNames);
  }

  public static void main(String[] args) throws IOException {
//    File relFile = new File("data/srl-reldata/srl-FNFUTXT1228670.rel");
    TNode.DEBUG = true;
    State.DEBUG = true;
    String base = "data/srl-reldata/srl-FNFUTXT1228670";
    File defsFile = new File(base + ".defs");
    File valuesFile = new File(base + ".values");
    Random rand = new Random(9001);
    Uberts u = new Uberts(rand);
    SrlPipeline srl = new SrlPipeline(u, defsFile, valuesFile);

    u.getState().dbgShowEdges();

    u.getAgenda().dbgShowScores();

    boolean oracle = false;
    int maxActions = 3;
    u.dbgRunInference(oracle, maxActions);
  }
}
