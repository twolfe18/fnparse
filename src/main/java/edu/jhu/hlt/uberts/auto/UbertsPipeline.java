package edu.jhu.hlt.uberts.auto;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.io.filefilter.AgeFileFilter;

import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Target;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.TemplateAlphabet;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Labels;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Relation.EqualityArray;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Step;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorBackwardsParser.Iter;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser.TG;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.hlt.uberts.features.OldFeaturesWrapper;
import edu.jhu.hlt.uberts.features.Weight;
import edu.jhu.hlt.uberts.features.WeightAdjoints;
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

  enum Mode {
    EXTRACT_FEATS,
    LEARN,
  }

  private Uberts u;
  private List<Rule> rules;
  private List<Relation> helperRelations;
  private TypeInference typeInf;

  private double pNegSkip = 0.75;
  private BasicFeatureTemplates bft;
  private OldFeaturesWrapper fe;
  private Mode mode;

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
    bft = new BasicFeatureTemplates();

    ExperimentProperties config = ExperimentProperties.getInstance();
    mode = Mode.valueOf(config.getString("mode"));
    if (mode == Mode.EXTRACT_FEATS) {
      fe = new OldFeaturesWrapper(bft, pNegSkip);
      fe.cacheAdjointsForwards = false;
    } else if (mode == Mode.LEARN) {
      fe = new OldFeaturesWrapper(bft);
      fe.cacheAdjointsForwards = true;
    }

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
      Log.info("read " + schemaRelations.size() + " schema relations from " + f.getPath());
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

//    if (r.rhs.relName.startsWith("event"))
//      tg.get2().feats = new FeatureExtractionFactor.Oracle(r.rhs.relName);
//    else
//      tg.get2().feats = fe;

    tg.get2().feats = new FeatureExtractionFactor.Oracle(r.rhs.relName);
    dbgTransitionGenerators.add(tg.get2());
    u.addTransitionGenerator(tg.get1(), tg.get2());
  }
  private List<TG> dbgTransitionGenerators = new ArrayList<>();

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

  public void evaluate(ManyDocRelationFileIterator instances) {
    Iter itr = new Iter(instances, typeInf, Arrays.asList("succTok"));
    while (itr.hasNext()) {
      RelDoc doc = itr.next();
      evaluate(doc);
    }
  }
  public void evaluate(RelDoc doc) {
    String docid = setupUbertsForDoc(u, doc);
    Log.info("evaluating on " + docid);
    boolean oracle = false;
    double minScore = 0;
    int actionLimit = 1000;
    Pair<Labels.Perf, List<Step>> pt = u.dbgRunInference(oracle, minScore, actionLimit);
//    Pair<Labels.Perf, List<Step>> pt = u.runLocalInference(oracle, minScore, actionLimit);
    Labels.Perf perf = pt.get1();
    Log.info("performance on " + docid);
    Map<String, FPR> pbr = perf.perfByRel();
    List<String> rels = new ArrayList<>(pbr.keySet());
    Collections.sort(rels);
    for (String rel : rels)
      System.out.println(rel + ":\t" + pbr.get(rel));

    System.out.println("traj.size=" + pt.get2().size() + " actionLimit=" + actionLimit);

    // Weights
    for (TG tg : dbgTransitionGenerators) {
      FeatureExtractionFactor.Oracle fe = (FeatureExtractionFactor.Oracle) tg.feats;
      for (Pair<Relation, Weight<String>> p : fe.getWeights())
        System.out.println(p.get1() + "\t" + p.get2() + "\t" + tg.getRule());
    }

    // False negatives
    int Nfn = 0;
    for (HypEdge e : perf.getFalseNegatives()) {
      if (e.getRelation().getName().equals("srl4"))
        continue;
      Nfn++;
      System.out.println("fn: " + e);
    }
    System.out.println(Nfn + " false negatives");

    System.out.println();
  }

  /**
   * @return the doc id.
   */
  public String setupUbertsForDoc(Uberts u, RelDoc doc) {
    boolean debug = false;

    u.getState().clearNonSchema();
    u.getAgenda().clear();
    u.initLabels();

    // Add an edge to the state specifying that we are working on this document/sentence.
    String docid = doc.def.tokens[1];
    HypNode docidN = u.lookupNode(docidNT, docid, true);
    u.addEdgeToState(u.makeEdge(startDocRel, docidN));

    int cx = 0, cy = 0;
    assert doc.items.isEmpty() && !doc.facts.isEmpty();

    // Idiosyncratic: change all event* edges from y to x
    for (HypEdge.WithProps fact : doc.facts) {
      if (fact.getRelation().getName().startsWith("event")) {
        Log.info("changing from y=>x: " + fact);
        fact.setProperty(HypEdge.IS_X, true);
        fact.setProperty(HypEdge.IS_Y, false);
      }
    }

    // Add all labels first
    for (HypEdge.WithProps fact : doc.facts) {
      if (fact.hasProperty(HypEdge.IS_Y)) {
        if (debug)
          System.out.println("[exFeats] y: " + fact);
        if (fact.hasProperty(HypEdge.IS_DERIVED))
          System.out.println("derived label: " + fact);
        u.addLabel(fact);
        cy++;
      }
    }

    // Add all state edges
    for (HypEdge.WithProps fact : doc.facts) {
      if (fact.hasProperty(HypEdge.IS_X)) {
        u.addEdgeToState(fact);
        if (debug)
          System.out.println("[exFeats] x: " + fact);
        if (fact.hasProperty(HypEdge.IS_DERIVED))
          System.out.println("derived state edge: " + fact);
        cx++;
      }
    }
    if (debug)
      Log.info("cx=" + cx + " cy=" + cy + " all=" + doc.facts.size());

    // Put out a notification that all of the annotations have been added.
    // Up to this, most actions will be blocked.
    HypEdge d = u.makeEdge(doneAnnoRel, docidN);
    u.addEdgeToState(d);

    Log.info("done setup on " + docid);
    return docid;
  }


  /**
   * Setup and run inference for left-to-right span-by-span role-classification
   * with no global features.
   *
   * for each role:
   *   argmax_{span \in xue-palmer-arg U {nullSpan}} score(t,f,k,s)
   */
  public void srlClassificationCopOut(RelDoc doc) {

    setupUbertsForDoc(u, doc);
    // This should put all srlArg edges onto the agenda

    State state = u.getState();
    Agenda agenda = u.getAgenda();

    Relation srlArg = u.getEdgeType("srlArg");
    List<HypEdge> srlArgs = new ArrayList<>();
    for (LL<HypEdge> cur = state.match2(srlArg); cur != null; cur = cur.next) {
      System.out.println(cur.item);
      srlArgs.add(cur.item);
    }

    // Clear the agenda and add from scratch
    agenda.clear();

    // Build a t -> [s] from xue-palmer-edges
    Relation xuePalmerArgs = u.getEdgeType("xue-palmer-args");
    Map<Span, LL<Span>> xuePalmerIndex = new HashMap<>();
    for (LL<HypEdge> cur = state.match2(xuePalmerArgs); cur != null; cur = cur.next) {
      HypEdge e = cur.item;
      assert e.getNumTails() == 4;
      int ts = Integer.parseInt((String) e.getTail(0).getValue());
      int te = Integer.parseInt((String) e.getTail(1).getValue());
      int ss = Integer.parseInt((String) e.getTail(2).getValue());
      int se = Integer.parseInt((String) e.getTail(3).getValue());
      Span key = Span.getSpan(ts, te);
      xuePalmerIndex.put(key, new LL<>(Span.getSpan(ss, se), xuePalmerIndex.get(key)));
    }

    // Do event1 * xue-palmer-args join manually since the multi-var-equality
    // rules are broken.
    Relation srl4 = u.getEdgeType("srl4");
    NodeType tokenIndex = u.lookupNodeType("tokenIndex", false);
    for (HypEdge tfk : srlArgs) {
      assert tfk.getNumTails() == 3;
      EqualityArray e1 = (EqualityArray) tfk.getTail(0).getValue();
      assert e1.length() == 2;
      int ts = Integer.parseInt((String) e1.get(0));
      int te = Integer.parseInt((String) e1.get(1));
      Span key = Span.getSpan(ts, te);
      for (LL<Span> cur = xuePalmerIndex.get(key); cur != null; cur = cur.next) {
        Span s = cur.item;
        HypNode[] tail = new HypNode[6];
        tail[0] = u.lookupNode(tokenIndex, String.valueOf(ts), true);
        tail[1] = u.lookupNode(tokenIndex, String.valueOf(te), true);
        tail[2] = tfk.getTail(1); // frame
        tail[3] = u.lookupNode(tokenIndex, String.valueOf(s.start), true);
        tail[4] = u.lookupNode(tokenIndex, String.valueOf(s.end), true);
        tail[5] = tfk.getTail(2); // role
        HypEdge yhat = u.makeEdge(srl4, tail);
        Adjoints a = fe.score(yhat, u);
        u.addEdgeToAgenda(yhat, a);
      }
      HypEdge best = agenda.pop();
      agenda.clear();

      if (u.getLabel(best)) {
        sdfkdsl
      } else {
        
      }
    }

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
    Log.info("mode=" + mode);

    // This will initialize the Alphabet over frame/role names and provide
    // the code to write out features.txt.gz and template-feat-indices.txt.gz
    FeaturePrecomputation.AlphWrapper fpre = null;
    if (fPreRoleNameAlph != null) {
      boolean roleMode = true;
      fpre = new FeaturePrecomputation.AlphWrapper(roleMode, fe.getFeatures());
    }

    Timer trajProcessingTimer = new Timer("trajProcessingTimer", 10, true);
    TimeMarker tm = new TimeMarker();
    Counts<String> posRel = new Counts<>(), negRel = new Counts<>();
    int docs = 0, actions = 0;
    int skippedDocs = 0;
    long features = 0;
    u.showOracleTrajDiagnostics = true;
    Iter itr = new Iter(x, typeInf, Arrays.asList("succTok"));
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      while (itr.hasNext()) {
        RelDoc doc = itr.next();
        if (dataShard != null && !dataShard.matches(doc.getId())) {
          skippedDocs++;
          continue;
        }
        docs++;

        if (true) {
          srlClassificationCopOut(doc);
          continue;
        }

        if (mode == Mode.LEARN && u.getRandom().nextDouble() < 0.5) {
          evaluate(doc);
          continue;
        }

        // Add all the edges that need to be there at the start of inference
        String docid = setupUbertsForDoc(u, doc);

        // Run inference and record extracted features
        boolean dedupEdges = true;
        int skipped = 0, kept = 0;
        List<Step> traj = u.recordOracleTrajectory(dedupEdges);
        FPR fpr = new FPR();
        trajProcessingTimer.start();
        for (Step t : traj) {
          boolean y = u.getLabel(t.edge);

          // LEARNING
          if (mode == Mode.LEARN) {
            double score = t.score.forwards();
            double margin = 0.1;
            if (y && score < margin)
              t.score.backwards(-1);
            if (!y && score > -margin)
              t.score.backwards(+1);
            fpr.accum(y, score > 0);
          } else {
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
              String lab = y ? "+1" : "-1";
              w.write(t.edge.getRelFileString(lab));
              for (Pair<TemplateAlphabet, String> tf : fx.getFeatures()) {
                w.write('\t');
                w.write(tf.get2()); // (template,feature), but feature includes template in the name
                features++;
              }
              w.newLine();
            }
          }

          if (y) posRel.increment(t.edge.getRelation().getName());
          else negRel.increment(t.edge.getRelation().getName());
//          if (debug)
//            System.out.println("[exFeats.orTraj] " + lab + " " + t.edge);// + " " + new HashableHypEdge(t.edge).hc);
          actions++;
        }
        trajProcessingTimer.stop();
        if (mode == Mode.LEARN) {
          Log.info("n=" + traj.size() + " model wrt weakOracle: " + fpr.toString());
//        } else if (mode == Mode.EXTRACT_FEATS) {
//          Log.info("skippedFeatures=" + skipped + " keptFeatures=" + kept
//              + " skippedDocs=" + skippedDocs + " keptDocs=" + docs);
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
