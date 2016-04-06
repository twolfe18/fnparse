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

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Rule;
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

  public SrlPipeline(
      Uberts u,
      File grammarFile,
      Iterable<File> schemaFiles,
      File xyDefsFile,
      File xyValuesFile) throws IOException {
    this.u = u;
    rules = new ArrayList<>();
    helperRelations = new ArrayList<>();

    // succTok(i,j)
    NodeType tokenIndex = u.lookupNodeType("tokenIndex", true);
    Relation succTok = u.addEdgeType(new Relation("succTok", tokenIndex, tokenIndex));
    helperRelations.add(succTok);

    HypNode prev = u.lookupNode(tokenIndex, String.valueOf(-1), true);
    for (int i = 0; i < 100; i++) {   // TODO figure out a better way to handle this...
      HypNode cur = u.lookupNode(tokenIndex, String.valueOf(i), true);
      u.addEdgeToState(u.makeEdge(succTok, prev, cur));
      prev = cur;
    }

    // Read in the defs file which defines (and creates w.r.t. Uberts) the
    // relations which will appear in the values file.
    Log.info("reading in relation/node type definitions...");
    u.readRelData(xyDefsFile);

    Log.info("reading schema files...");
    for (File f : schemaFiles) {
      List<Relation> schemaRelations = u.readRelData(f);
      helperRelations.addAll(schemaRelations);
    }

    for (Rule r : Rule.parseRules(grammarFile, u))
      addRule(r);

    // Read in the x,y data
    Log.info("reading in x,y data...");
    try (BufferedReader r = FileUtil.getReader(xyValuesFile)) {
      u.readRelData(r);
    }
    System.out.println();
  }

  private void addRule(Rule r) {
    Log.info("adding " + r);
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
    for (Relation r : getHelperRelations()) {
      Log.info("skipping " + r);
      s.add(r);
    }
    return s;
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties.init(args);
//    TNode.DEBUG = true;
//    State.DEBUG = true;
//    Agenda.DEBUG = true;
    String base = "data/srl-reldata/srl-FNFUTXT1228670";
    File xyDefsFile = new File(base + ".defs");
    File xyValuesFile = new File(base + ".values");
    List<File> schemaFiles = Arrays.asList(
        new File("data/srl-reldata/frameTriage2.propbank.rel"),
        new File("data/srl-reldata/role2.propbank.rel.gz"));
    File grammarFile = new File("data/srl-reldata/srl-grammar.trans");
    File output = new File(base + ".predicted.values");
    Random rand = new Random(9001);
    Uberts u = new Uberts(rand);
    SrlPipeline srl = new SrlPipeline(u, grammarFile, schemaFiles, xyDefsFile, xyValuesFile);

    boolean oracle = false;
    int maxActions = 0;
    u.dbgRunInference(oracle, maxActions);

    Log.info("writing edges to " + output.getPath());
    Set<Relation> skip = srl.getHelperRelationsAsSet();
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      u.getState().writeEdges(w, skip);
    }

    Log.info("done");
  }
}
