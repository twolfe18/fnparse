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

import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.tutils.Counts;
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
import edu.jhu.hlt.uberts.auto.TransitionGeneratorBackwardsParser.Iter;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser.TG;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor.WeightAdjoints;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
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
  private TypeInference typeInf;

  // Both of these are single arg relations and their argument is a doc id.
  private NodeType docidNT;
  private Relation startDocRel;
  private Relation doneAnnoRel;

  public UbertsPipeline(
      Uberts u,
      File grammarFile,
      Iterable<File> schemaFiles,
      File xyDefsFile) throws IOException {
    this.u = u;
    rules = new ArrayList<>();
    helperRelations = new ArrayList<>();

    // succTok(i,j)
    helperRelations.add(u.addSuccTok(1000));

//    hookKeywordNT = u.lookupNodeType("hookKeyword", true);
//    startDocN = u.lookupNode(hookKeywordNT, "startdoc", true);
//    docIdNT = u.lookupNodeType("docid", true);

//    hook2Rel = u.addEdgeType(new Relation("hook2", hookKeywordNT, docIdNT));
//    hook2Rel = u.readRelData("def hook2 <hookKeyword> <docid>");
    startDocRel = u.readRelData("def startDoc <docid>");
    doneAnnoRel = u.readRelData("def doneAnno <docid>");
    docidNT = doneAnnoRel.getTypeForArg(0);

    Log.info("reading schema files...");
    for (File f : schemaFiles) {
      List<Relation> schemaRelations = u.readRelData(f);
      helperRelations.addAll(schemaRelations);
    }

    // Read in the defs file which defines (and creates w.r.t. Uberts) the
    // relations which will appear in the values file.
    Log.info("reading in relation/node type definitions...");
    u.readRelData(xyDefsFile);

    this.typeInf = new TypeInference(u);
//    this.typeInf.debug = true;
    for (Rule untypedRule : Rule.parseRules(grammarFile, null))
      typeInf.add(untypedRule);
    for (Rule typedRule : typeInf.runTypeInference()) {
      if (typedRule.comment != null && typedRule.comment.toLowerCase().contains("nopredict")) {
        Log.info("not including in transition system because of NOPREDICT comment: " + typedRule);
        continue;
      }
      addRule(typedRule);
    }
  }

  private BasicFeatureTemplates bft = new BasicFeatureTemplates();
  private FeatureExtractionFactor<String> fe = new FeatureExtractionFactor.OldFeaturesWrapper(bft);
  private void addRule(Rule r) {
    Log.info("adding " + r);
    rules.add(r);

    // Add to Uberts as a TransitionGenerator
    TransitionGeneratorForwardsParser tgfp = new TransitionGeneratorForwardsParser();
    Pair<List<TKey>, TG> tg = tgfp.parse2(r, u);

//    List<Relation> relevant = Arrays.asList(r.rhs.rel);
//    tg.get2().feats = new FeatureExtractionFactor.Simple(relevant, u);
//    tg.get2().feats = new FeatureExtractionFactor.GraphWalks();
//    tg.get2().feats = new FeatureExtractionFactor.OldFeaturesWrapper(bft);
    tg.get2().feats = fe;
    // If you leave it null, it will assign everything a score of 1

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
    Log.info("writing features to " + output);

    boolean debug = true;
    TimeMarker tm = new TimeMarker();
    Counts<String> posRel = new Counts<>(), negRel = new Counts<>();
    int docs = 0, actions = 0;
    long features = 0;
    List<String> ignore = Arrays.asList("succTok");
    Iter itr = new Iter(x, typeInf, ignore);
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      while (itr.hasNext()) {
        RelDoc doc = itr.next();
        docs++;

        u.getState().clearNonSchema();
        u.getAgenda().clear();
        u.initLabels();

        // Add an edge to the state specifying that we are working on this document/sentence.
        String docid = doc.def.tokens[1];
        HypNode docidN = u.lookupNode(docidNT, docid, true);
        u.addEdgeToState(u.makeEdge(startDocRel, docidN));

        // Add x:HypEdges to State
        // Add y:HypEdges as labels
        int cx = 0, cy = 0;
        assert doc.items.isEmpty() && !doc.facts.isEmpty();
        for (HypEdge.WithProps fact : doc.facts) {
          if (fact.hasProperty(HypEdge.IS_Y)) {
            if (debug) {
              //                HashableHypEdge hhe = new HashableHypEdge(fact);
              //                System.out.println("[exFeats] adding: " + hhe.hashDesc());
              System.out.println("[exFeats] y: " + fact);
            }
            u.addLabel(fact);
            cy++;
          }
        }
        for (HypEdge.WithProps fact : doc.facts) {
          if (fact.hasProperty(HypEdge.IS_X)) {
            u.addEdgeToState(fact);
            System.out.println("[exFeats] x: " + fact);
            cx++;
          }
        }
        if (debug) {
          Log.info("cx=" + cx + " cy=" + cy + " all=" + doc.facts.size());
          w.flush();
        }


        // Put out a notification that all of the annotations have been added.
        // Up to this, most actions will be blocked.
        u.addEdgeToState(u.makeEdge(doneAnnoRel, docidN));


        // Run inference and record extracted features
        boolean dedupEdges = true;
        List<Step> traj = u.recordOracleTrajectory(dedupEdges);
        for (Step t : traj) {
          boolean y = u.getLabel(t.edge);
          if (y) posRel.increment(t.edge.getRelation().getName());
          else negRel.increment(t.edge.getRelation().getName());
          String lab = y ? "+1" : "-1";
//          if (debug)
//            System.out.println("[exFeats.orTraj] " + lab + " " + t.edge);// + " " + new HashableHypEdge(t.edge).hc);
          actions++;
          @SuppressWarnings("unchecked")
          WeightAdjoints<String> fx = (WeightAdjoints<String>) t.score;
          w.write(t.edge.getRelFileString(lab));
          for (String feat : fx.getFeatures()) {
            w.write('\t');
            w.write(feat);
            features++;
          }
          w.newLine();
        }

        if (tm.enoughTimePassed(15)) {
          double sec = tm.secondsSinceFirstMark();
          double fPerD = features / ((double) docs);
          double fPerA = features / ((double) actions);
          String msg = String.format(
              "extracted %d features for %d docs (%.1f feat/doc) and %d actions (%.1f feat/act) in %.1f seconds",
              features, docs, fPerD, actions, fPerA, sec);
          Log.info(msg);
          double aPerD = actions / ((double) docs);
          msg = String.format("%.1f doc/sec, %.1f act/sec, %.1f act/doc",
              docs/sec, actions/sec, aPerD);
          Log.info(msg);
          Log.info("numPosFeatsExtracted: " + posRel + " sum=" + posRel.getTotalCount());
          Log.info("numNegFeatsExtracted: " + negRel + " sum=" + negRel.getTotalCount());
          w.flush();
        }
      }
    }
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties.init(args);

    File xyDefsFile = new File("data/srl-reldata/propbank/relations.def");
    List<File> schemaFiles = Arrays.asList(
        new File("data/srl-reldata/frameTriage2.propbank.rel"),
        new File("data/srl-reldata/role2.propbank.rel.gz"));
//    File grammarFile = new File("data/srl-reldata/srl-grammar.hobbs.trans");
    File grammarFile = new File("data/srl-reldata/srl-grammar-moreArgs.hobbs.trans");
    Random rand = new Random(9001);
    Uberts u = new Uberts(rand);
    UbertsPipeline srl = new UbertsPipeline(u, grammarFile, schemaFiles, xyDefsFile);

//    TNode.DEBUG = true;
//    State.DEBUG = true;
//    Agenda.DEBUG = true;
//    Uberts.DEBUG = true;

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
    boolean includeProvidence = false;
    boolean dedupInputLines = true;
    try (RelationFileIterator itr = new RelationFileIterator(multiXY, includeProvidence);
        ManyDocRelationFileIterator x  = new ManyDocRelationFileIterator(itr, dedupInputLines)) {
      srl.extractFeatures(x, multiYhat);
    }

    Log.info("done");
  }
}
