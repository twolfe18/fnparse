package edu.jhu.hlt.uberts.srl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Rule;
import edu.jhu.hlt.uberts.auto.Term;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser.TG;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.prim.tuple.Pair;

/**
 * Just get SRL working end-to-end.
 *
 * @author travis
 */
public class SrlPipeline {
  private Uberts u;
  private List<Rule> rules;
  private List<Relation> helperRelations;

  public SrlPipeline(Uberts u, File defsFile, File valuesFile) throws IOException {
    this.u = u;
    rules = new ArrayList<>();
    helperRelations = new ArrayList<>();

    // succTok(i,j)
    NodeType tokenIndex = u.lookupNodeType("tokenIndex", true);
    Relation succTok = u.addEdgeType(new Relation("succTok", tokenIndex, tokenIndex));
    HypNode prev = u.lookupNode(tokenIndex, String.valueOf(-1), true);
    for (int i = 0; i < 100; i++) {   // TODO figure out a better way to handle this...
      HypNode cur = u.lookupNode(tokenIndex, String.valueOf(i), true);
      u.addEdgeToState(u.makeEdge(succTok, prev, cur));
      prev = cur;
    }

    // Read in the defs file which defines (and creates w.r.t. Uberts) the
    // relations which will appear in the values file.
    Log.info("reading in relation/node type definitions...");
    try (BufferedReader r = FileUtil.getReader(defsFile)) {
      u.readRelData(r);
    }


    // frameTriage2(lemma,frame)
    buildFrameTriageRelation(u);
    // role(frame,role)
    buildRoleRelation(u);


    // All single-word targets are possible
    addRule("pos2(i,t) & succTok(i,j) => event1(i,j)");

    // Build a table of (lemma,frame) for triage
    addRule("event1(i,j) & lemma2(i,l) & frameTriage2(l,f) => event2(i,j,f)");

    // Args can be any constituent in the stanford parse
    addRule("csyn3-stanford(i,j,lhs) => srl1(i,j)");

    addRule("srl1(i,j) & event1(a,b) => srl2(a,b,i,j)");
    addRule("event1(a,b) & srl1(i,j) => srl2(a,b,i,j)");

    addRule("srl2(a,b,i,j) & event2(a,b,f) & role(f,k) => srl3(a,b,f,i,j,k)");
    addRule("event2(a,b,f) & srl2(a,b,i,j) & role(f,k) => srl3(a,b,f,i,j,k)");


    // Read in the x,y data
    Log.info("reading in x,y data...");
    try (BufferedReader r = FileUtil.getReader(valuesFile)) {
      u.readRelData(r);
    }
    System.out.println();
  }

  // TODO Make a rel file with this as contents
  public void buildFrameTriageRelation(Uberts u) {
    NodeType lemma = u.lookupNodeType("lemma", false);
    NodeType frame = u.lookupNodeType("frame", false);
    Relation frameTriage2 = u.addEdgeType(new Relation("frameTriage2", lemma, frame));
    helperRelations.add(frameTriage2);
    FrameIndex fi = FrameIndex.getPropbank();
    for (Frame f : fi.allFrames()) {
      String lemmaStr = f.getName().split("\\W+")[1];
      HypNode lemmaNode = u.lookupNode(lemma, lemmaStr, true);
      HypNode frameNode = u.lookupNode(frame, f.getName(), true);
      HypEdge e = u.makeEdge(frameTriage2, lemmaNode, frameNode);
      u.addEdgeToState(e);
    }
  }

  // TODO Make a rel file with this as contents
  public void buildRoleRelation(Uberts u) {
    NodeType frame = u.lookupNodeType("frame", false);
    NodeType roleNT = u.lookupNodeType("roleLabel", false);
    Relation role = u.addEdgeType(new Relation("role", frame, roleNT));
    helperRelations.add(role);
    FrameIndex fi = FrameIndex.getPropbank();
    for (Frame f : fi.allFrames()) {
      int K = f.numRoles();
      HypNode fNode = u.lookupNode(frame, f.getName(), true);
      for (int k = 0; k < K; k++) {
        String ks = f.getRole(k);
        HypNode kNode = u.lookupNode(roleNT, ks, true);
        HypEdge e = u.makeEdge(role, fNode, kNode);
        u.addEdgeToState(e);
      }
    }
  }

  private void addRule(String rule) {
    Log.info("adding " + rule);

    // Parse and save
    Rule r = parseRule(rule, u);
    rules.add(r);

    // Add to Uberts as a TransitionGenerator
    TransitionGeneratorForwardsParser tgfp = new TransitionGeneratorForwardsParser();
    Pair<List<TKey>, TG> tg = tgfp.parse2(r, u);

    List<Relation> relevant = Arrays.asList(r.rhs.rel);
    tg.get2().feats = new FeatureExtractionFactor.Simple(relevant, u);

    u.addTransitionGenerator(tg.get1(), tg.get2());
  }

  public List<Relation> getHelperRelations() {
    return helperRelations;
  }
  public Set<Relation> getHelperRelationsAsSet() {
    Set<Relation> s = new HashSet<>();
    for (Relation r : getHelperRelations())
      s.add(r);
    return s;
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
    ExperimentProperties.init(args);

//    File relFile = new File("data/srl-reldata/srl-FNFUTXT1228670.rel");
//    TNode.DEBUG = true;
//    State.DEBUG = true;
    String base = "data/srl-reldata/srl-FNFUTXT1228670";
    File defsFile = new File(base + ".defs");
    File valuesFile = new File(base + ".values");
    File output = new File(base + ".predicted.values");
    Random rand = new Random(9001);
    Uberts u = new Uberts(rand);
    SrlPipeline srl = new SrlPipeline(u, defsFile, valuesFile);

//    u.getState().dbgShowEdges();

//    u.getAgenda().dbgShowScores();

    boolean oracle = false;
    int maxActions = 10;
    u.dbgRunInference(oracle, maxActions);

    Log.info("writing edges to " + output.getPath());
    Set<Relation> skip = srl.getHelperRelationsAsSet();
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      u.getState().writeEdges(w, skip);
    }

    Log.info("done");
  }
}
