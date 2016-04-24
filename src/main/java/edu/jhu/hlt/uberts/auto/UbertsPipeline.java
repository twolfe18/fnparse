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
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Target;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.TemplateAlphabet;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
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
import edu.jhu.hlt.uberts.srl.Srl3EdgeWrapper;
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

  private double pNegSkip = 0.75;
  private BasicFeatureTemplates bft = new BasicFeatureTemplates();
  private FeatureExtractionFactor.OldFeaturesWrapper fe = new FeatureExtractionFactor.OldFeaturesWrapper(bft, pNegSkip);

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

    Log.info("running type inference...");
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
    Log.info("done");
  }

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

  private File fPreTemplateFeatureAlph;
  private File fPreRoleNameAlph;
  /**
   * Call this method to make the feature output look like {@link FeaturePrecomputation}.
   * @param fPreTemplateFeatureAlph is where to write the 4-column tsv for
   * templates and features and their indices. Typically called template-feat-indices.txt.gz.
   * @param fPreRoleNameAlph is where to write the frame/role/frame role names
   * (the column referred to as "k"). Typically called role-names.txt.gz.
   */
  public void setFeaturePrecomputationMode(File fPreTemplateFeatureAlph, File fPreRoleNameAlph) {
    assert fPreRoleNameAlph != null;
    assert fPreTemplateFeatureAlph != null;
    this.fPreRoleNameAlph = fPreRoleNameAlph;
    this.fPreTemplateFeatureAlph = fPreTemplateFeatureAlph;
  }

  /**
   * Extracts features off of all actions generated from the transition grammar
   * and the x rel data given.
   *
   * NOTE: Make sure you don't add any mutual exclusion factors (e.g. AtMost1)
   * before running this: we don't want to remove negative HypEdges which are
   * ruled out by choosing the right one first.
   *
   * @param dataShard may be null, meaning take all data.
   */
  public void extractFeatures(ManyDocRelationFileIterator x, File output, Shard dataShard) throws IOException {
    Log.info("writing features to " + output);
    Log.info("pSkipNeg=" + pNegSkip);

    // This will initialize the Alphabet over frame/role names and provide
    // the code to write out features.txt.gz and template-feat-indices.txt.gz
    FeaturePrecomputation.AlphWrapper fpre = null;
    if (fPreRoleNameAlph != null)
      fpre = new FeaturePrecomputation.AlphWrapper(true, fe.getFeatures());

    boolean debug = false;
    TimeMarker tm = new TimeMarker();
    Counts<String> posRel = new Counts<>(), negRel = new Counts<>();
    int docs = 0, actions = 0;
    int skippedDocs = 0;
    long features = 0;
    List<String> ignore = Arrays.asList("succTok");
    Iter itr = new Iter(x, typeInf, ignore);
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      while (itr.hasNext()) {
        RelDoc doc = itr.next();
        if (dataShard != null && !dataShard.matches(doc.getId())) {
          skippedDocs++;
          continue;
        }
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
            if (debug)
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
        int skipped = 0, kept = 0;
        List<Step> traj = u.recordOracleTrajectory(dedupEdges);
        for (Step t : traj) {
          boolean y = u.getLabel(t.edge);
          String lab = y ? "+1" : "-1";

          @SuppressWarnings("unchecked")
          WeightAdjoints<Pair<TemplateAlphabet, String>> fx =
              (WeightAdjoints<Pair<TemplateAlphabet, String>>) t.score;
          if (fx.getFeatures() == fe.SKIP) {
            skipped++;
            continue;
          }
          kept++;

          // OUTPUT
          if (fpre != null) {
            // TODO a lot of overlap between features(srl2) and features(srl3)?
            // TODO do I need to group-by (t,s) for this output format?
            // I think the solution which satisfies both of those problems is to
            // => only write out srl3 edges
            // this way you know how to format the (t,s,k) fields
            if (t.edge.getRelation().getName().equals("srl3")) {
              Srl3EdgeWrapper s3 = new Srl3EdgeWrapper(t.edge);
              String k;
              if (y) {
                k = fpre.lookupRole(s3.k)
                    + "," + fpre.lookupFrameRole(s3.f, s3.k)
                    + "," + fpre.lookupFrame(s3.f);
              } else {
                k = "-1";
              }
              w.write(Target.toLine(new FeaturePrecomputation.Target(docid, s3.t)));
              w.write("\t" + s3.s.shortString());
              w.write("\t" + k);
              for (Pair<TemplateAlphabet, String> tf : fx.getFeatures()) {
                TemplateAlphabet template = tf.get1();
                String feat = tf.get2();
                int featIdx = template.alph.lookupIndex(feat, true);
                w.write('\t');
                w.write(template.index + ":" + featIdx);
                features++;
              }
              w.newLine();
            }
          } else {
            // Writes out tab-separated human readable features
            w.write(t.edge.getRelFileString(lab));
            for (Pair<TemplateAlphabet, String> tf : fx.getFeatures()) {
              w.write('\t');
              w.write(tf.get2()); // (template,feature), but feature includes template in the name
              features++;
            }
            w.newLine();
          }

          if (y) posRel.increment(t.edge.getRelation().getName());
          else negRel.increment(t.edge.getRelation().getName());
//          if (debug)
//            System.out.println("[exFeats.orTraj] " + lab + " " + t.edge);// + " " + new HashableHypEdge(t.edge).hc);
          actions++;
        }
        Log.info("skippedFeatures=" + skipped + " keptFeatures=" + kept
            + " skippedDocs=" + skippedDocs + " keptDocs=" + docs);

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

    if (fPreRoleNameAlph != null)
      fpre.saveAlphabets(fPreTemplateFeatureAlph, fPreRoleNameAlph);
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

//    TNode.DEBUG = true;
//    State.DEBUG = true;
//    Agenda.DEBUG = true;
//    Uberts.DEBUG = true;

//    File xyDefsFile = new File("data/srl-reldata/propbank/relations.def");
    File xyDefsFile = config.getExistingFile("relationDefs");

//    List<File> schemaFiles = Arrays.asList(
//        new File("data/srl-reldata/frameTriage2.propbank.rel"),
//        new File("data/srl-reldata/role2.propbank.rel.gz"));
    List<File> schemaFiles = config.getExistingFiles("schemaFiles");

//    File grammarFile = new File("data/srl-reldata/srl-grammar.hobbs.trans");
//    File grammarFile = new File("data/srl-reldata/srl-grammar-moreArgs.hobbs.trans");
    File grammarFile = config.getExistingFile("grammarFile");

    Random rand = new Random(9001);
    Uberts u = new Uberts(rand);
    UbertsPipeline srl = new UbertsPipeline(u, grammarFile, schemaFiles, xyDefsFile);

//    File multiXY = new File("data/srl-reldata/propbank/instances.rel.multi.gz");
//    File multiYhat = new File("data/srl-reldata/propbank/instances.yhat.rel.multi.gz");
    File multiXY = config.getExistingFile("inputRel");
    File multiYhat = config.getFile("outputRel");

    String k = "outputTemplateFeatureAlph";
    if (config.containsKey(k)) {
      Log.info("mimicing output of FeaturePrecomputation");
      srl.setFeaturePrecomputationMode(
          config.getFile(k),
          config.getFile("outputRoleAlph"));
    }

    Shard dataShard = config.getShard();
    boolean includeProvidence = false;
    boolean dedupInputLines = true;
    try (RelationFileIterator itr = new RelationFileIterator(multiXY, includeProvidence);
        ManyDocRelationFileIterator x  = new ManyDocRelationFileIterator(itr, dedupInputLines)) {
      srl.extractFeatures(x, multiYhat, dataShard);
    }

    Log.info("done");
  }
}
