package edu.jhu.hlt.uberts.auto;

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
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.Uberts.Step;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser.TG;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor.WeightAdjoints;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;
import edu.jhu.prim.tuple.Pair;

/**
 * Takes in Uberts pieces (e.g. transition grammar and labels) and runs inference.
 *
 * @author travis
 */
public class UbertsPipeline {

  private Uberts u;
  private List<Rule> rules;
  private List<Relation> helperRelations;

  public UbertsPipeline(
      Uberts u,
      File grammarFile,
      Iterable<File> schemaFiles,
      File xyDefsFile) throws IOException {
    this.u = u;
    rules = new ArrayList<>();
    helperRelations = new ArrayList<>();

    // succTok(i,j)
    helperRelations.add(u.addSuccTok(100));

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
  }

  public void addRelData(File xyValuesFile) throws IOException {
    // Read in the x,y data
    Log.info("reading in x,y data...");
    try (BufferedReader r = FileUtil.getReader(xyValuesFile)) {
      u.readRelData(r);
    }
    System.out.println();
  }

  /**
   * Extracts features off of all actions generated from the transition grammar
   * and the x rel data given.
   *
   * NOTE: Make sure you don't add any mutual exclusion factors (e.g. AtMost1)
   * before running this: we don't want to remove negative HypEdges which are
   * ruled out by choosing the right one first.
   */
  public void extractFeatures(ManyDocRelationFileIterator x, File output) throws IOException {
    TimeMarker tm = new TimeMarker();
    int docs = 0, actions = 0;
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      while (x.hasNext()) {
        RelDoc doc = x.next();
        docs++;

        u.getState().clearNonSchema();
        u.getAgenda().clear();
        u.initLabels();

        // Add x:HypEdges to State
        // Add y:HypEdges as labels
        for (RelLine line : doc.items) {
          if (line.isX() || line.isY()) {
            HypEdge e = u.makeEdge(line);
            if (line.isX())
              u.addEdgeToState(e);
            else
              u.addLabel(e);
          } else {
            Log.warn("why: " + line);
          }
        }

        // Run inference and record extracted features
        List<Step> traj = u.recordOracleTrajectory();
        for (Step t : traj) {
          actions++;
          @SuppressWarnings("unchecked")
          WeightAdjoints<String> fx = (WeightAdjoints<String>) t.score;
          String dtype = "";
          w.write(t.edge.getRelFileString(dtype));
          for (String feat : fx.getFeatures()) {
            w.write('\t');
            w.write(feat);
          }
          w.newLine();
        }

        if (tm.enoughTimePassed(15)) {
          Log.info("extracted features for " + docs + " docs and " + actions
              + " actions in " + tm.secondsSinceFirstMark() + " seconds");
          w.flush();
        }
      }
    }
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

    File xyDefsFile = new File("data/srl-reldata/propbank/relations.def");
    List<File> schemaFiles = Arrays.asList(
        new File("data/srl-reldata/frameTriage2.propbank.rel"),
        new File("data/srl-reldata/role2.propbank.rel.gz"));
    File grammarFile = new File("data/srl-reldata/srl-grammar.trans");
    Random rand = new Random(9001);
    Uberts u = new Uberts(rand);
    UbertsPipeline srl = new UbertsPipeline(u, grammarFile, schemaFiles, xyDefsFile);

//    String base = "data/srl-reldata/srl-FNFUTXT1228670";
//    File xyDefsFile = new File(base + ".defs");
//    File xyValuesFile = new File(base + ".values");
//    srl.addRelData(xyValuesFile);
//
//    boolean oracle = false;
//    int maxActions = 0;
//    u.dbgRunInference(oracle, maxActions);
//
//    File output = new File(base + ".predicted.values");
//    Log.info("writing edges to " + output.getPath());
//    Set<Relation> skip = srl.getHelperRelationsAsSet();
//    try (BufferedWriter w = FileUtil.getWriter(output)) {
//      u.getState().writeEdges(w, skip);
//    }

    File multiXY = new File("data/srl-reldata/propbank/instances.rel.multi.gz");
    File multiYhat = new File("data/srl-reldata/propbank/instances.yhat.rel.multi.gz");
    try (RelationFileIterator itr = new RelationFileIterator(multiXY);
        ManyDocRelationFileIterator x  = new ManyDocRelationFileIterator(itr)) {
      srl.extractFeatures(x, multiYhat);
    }

    Log.info("done");
  }
}
