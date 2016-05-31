package edu.jhu.hlt.uberts.auto;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.TemplateContext;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures;
import edu.jhu.hlt.fnparse.features.precompute.FeatureSet;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.data.BrownClusters;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Labels;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Step;
import edu.jhu.hlt.uberts.TNode;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorBackwardsParser.Iter;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser.TG;
import edu.jhu.hlt.uberts.factor.LocalFactor;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.prim.tuple.Pair;

/**
 * A super-class for implementing functionality which needs to scan over a
 * multi-rel file. Implements some common stuff around rule parsing, type
 * inference, and other setup.
 *
 * @author travis
 */
public abstract class UbertsPipeline {
  public static int DEBUG = 1;  // 0 means off, 1 means coarse, 2+ means fine grain logging

  protected Uberts u;
  protected List<Rule> rules;
  protected Set<Relation> rhsOfRules;
  protected List<TG> transitionGenerators;
  protected List<Relation> helperRelations;
  protected TypeInference typeInf;

  // Both of these are single arg relations and their argument is a doc id.
  protected NodeType docidNT;
  protected Relation startDocRel;
  protected Relation doneAnnoRel;

  protected Set<String> dontBackwardsGenerate;

  public boolean debug = false;
  public MultiTimer timer;

  /**
   * @param u
   * @param grammarFile should be type-able from relation defs
   * @param schemaFiles
   * @param relationDefs is read in first
   */
  public UbertsPipeline(
      Uberts u,
      File grammarFile,
      Iterable<File> schemaFiles,
      File relationDefs) throws IOException {
    this.u = u;
    timer = new MultiTimer();
    timer.put("setupUbertsForDoc", new Timer("setupUbertsForDoc", 30, true));
//    timer.put("adHocSrlClassificationByRole.setup", new Timer("adHocSrlClassificationByRole.setup", 30, true));
//    timer.put("adHocSrlClassificationByRole.prediction", new Timer("adHocSrlClassificationByRole.prediction", 30, true));
//    timer.put("adHocSrlClassificationByRole.update", new Timer("adHocSrlClassificationByRole.update", 30, true));

    dontBackwardsGenerate = new HashSet<>();
    dontBackwardsGenerate.add("succTok");

    rules = new ArrayList<>();
    rhsOfRules = new HashSet<>();
    transitionGenerators = new ArrayList<>();
    helperRelations = new ArrayList<>();

    // succTok(i,j)
    helperRelations.add(u.addSuccTok(1000));

    startDocRel = u.readRelData("def startDoc <docid>");
    doneAnnoRel = u.readRelData("def doneAnno <docid>");
    docidNT = doneAnnoRel.getTypeForArg(0);

    // Read in the defs file which defines (and creates w.r.t. Uberts) the
    // relations which will appear in the values file.
    if (DEBUG > 0)
      Log.info("reading in relation/node type definitions...");
    u.readRelData(relationDefs);

    Log.info("reading schema files...");
    for (File f : schemaFiles) {
      List<Relation> schemaRelations = u.readRelData(f);
      if (DEBUG > 0)
        Log.info("read " + schemaRelations.size() + " schema relations from " + f.getPath());
      helperRelations.addAll(schemaRelations);
      for (Relation r : schemaRelations)
        dontBackwardsGenerate.add(r.getName());
    }

    if (DEBUG > 0)
      Log.info("running type inference...");
    this.typeInf = new TypeInference(u);
//    this.typeInf.debug = true;
    for (Rule untypedRule : Rule.parseRules(grammarFile, null))
      typeInf.add(untypedRule);
    for (Rule typedRule : typeInf.runTypeInference()) {
      if (typedRule.comment != null && typedRule.comment.toLowerCase().contains("nopredict")) {
        if (DEBUG > 0)
          Log.info("not including in transition system because of NOPREDICT comment: " + typedRule);
        continue;
      }
      addRule(typedRule);
    }

    ExperimentProperties config = ExperimentProperties.getInstance();
    double lhsInRhsScoreScale = config.getDouble("lhsInRhsScoreScale", 0);
    Log.info("[main] lhsInRhsScoreScale=" + lhsInRhsScoreScale);
    assert lhsInRhsScoreScale >= 0;
    if (lhsInRhsScoreScale > 0) {
      for (TG tg : transitionGenerators) {
        tg.lhsInRhsScoreScale = lhsInRhsScoreScale;
        Rule r = tg.getRule();
        for (Term lhsT : r.lhs) {
          assert lhsT.rel != null;
          if (rhsOfRules.contains(lhsT.rel)) {
            if (tg.addLhsScoreToRhsScore == null)
              tg.addLhsScoreToRhsScore = new HashSet<>();
            tg.addLhsScoreToRhsScore.add(lhsT.rel);
          }
        }
        if (tg.addLhsScoreToRhsScore != null)
          Log.info("using lhs scores of " + tg.addLhsScoreToRhsScore + " to score rhs of " + tg.getRule());
      }
    }

    Log.info("done");
  }

  public abstract LocalFactor getScoreFor(Rule r);

  public abstract void consume(RelDoc doc);

  public void start(String dataName) {
    Log.info("starting on " + dataName);
  }

  public void finish(String dataName) {
    Log.info("finished with " + dataName);
  }

  private void addRule(Rule r) {
    assert r.rhs.rel != null;
    assert r.rhs.allArgsAreTyped();
    for (Term lt : r.lhs)
      assert lt.allArgsAreTyped();

    rules.add(r);
    rhsOfRules.add(r.rhs.rel);

    LocalFactor phi = getScoreFor(r);
    // Add to Uberts as a TransitionGenerator
    // Create all orderings of this rule
    for (Rule rr : Rule.allLhsOrders(r)) {

      if (helperRelations.contains(rr.lhs[0].rel)) {
        if (DEBUG > 0)
          Log.info("not adding this rule since first Functor is schema type: " + rr);
        continue;
      }

      TransitionGeneratorForwardsParser tgfp = new TransitionGeneratorForwardsParser();
      Pair<List<TKey>, TG> tg = tgfp.parse2(rr, u);

      if (DEBUG > 0) {
        Log.info("adding: " + rr);
        if (DEBUG > 1)
          System.out.println(StringUtils.join("\n", tg.get1()));
      }

      tg.get2().feats = phi;
      transitionGenerators.add(tg.get2());
      TNode tnode = u.addTransitionGenerator(tg.get1(), tg.get2());
      tnode.getValue().r = rr;
    }
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


  public void evaluate(ManyDocRelationFileIterator instances) {
    Iter itr = new Iter(instances, typeInf, dontBackwardsGenerate);
    while (itr.hasNext()) {
      RelDoc doc = itr.next();
      evaluate(doc);
    }
  }

  /**
   * General purpose (calls Uberts.dbgRunInference and follows the transition
   * system). Reports performance for each Relation.
   */
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

//    // Weights
//    for (TG tg : dbgTransitionGenerators) {
//      FeatureExtractionFactor.Oracle fe = (FeatureExtractionFactor.Oracle) tg.feats;
//      for (Pair<Relation, Weight<String>> p : fe.getWeights())
//        System.out.println(p.get1() + "\t" + p.get2() + "\t" + tg.getRule());
//    }

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

  static String prependRefinementTemplate(String refinement, File featureSet) {
    String fs = FeatureSet.getFeatureSetString(featureSet);
    List<String> features = TemplatedFeatures.tokenizeTemplates(fs);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < features.size(); i++) {
      if (i > 0) sb.append(" + ");
      sb.append(refinement);
      sb.append('*');
      sb.append(features.get(i));
    }
    return sb.toString();
  }

  /**
   * @return the doc id.
   */
  public String setupUbertsForDoc(Uberts u, RelDoc doc) {
    boolean debug = false;
    timer.start("setupUbertsForDoc");
    u.getState().clearNonSchema();
    u.getAgenda().clear();
    u.initLabels();


    // Add an edge to the state specifying that we are working on this document/sentence.
    String docid = doc.def.tokens[1];
    HypNode docidN = u.lookupNode(docidNT, docid, true);
    u.addEdgeToState(u.makeEdge(startDocRel, docidN), Adjoints.Constant.ZERO);

    int cx = 0, cy = 0;
    assert doc.items.isEmpty();
    assert !doc.facts.isEmpty() : "facts: " + doc.facts;

//    // Idiosyncratic: change all event* edges from y to x
//    for (HypEdge.WithProps fact : doc.facts) {
//      if (fact.getRelation().getName().startsWith("event")) {
//        if (this.debug)
//          Log.info("changing from y=>x: " + fact);
//        fact.setProperty(HypEdge.IS_X, true);
//        fact.setProperty(HypEdge.IS_Y, false);
//      }
//    }

    // Add all labels first
    for (HypEdge.WithProps fact : doc.facts) {
      if (fact.hasProperty(HypEdge.IS_Y)) {
        if (debug)
          System.out.println("[exFeats] y: " + fact);
        if (this.debug && fact.hasProperty(HypEdge.IS_DERIVED))
          System.out.println("derived label: " + fact);
        u.addLabel(fact);
        cy++;
      }
    }

    // Add all state edges
    for (HypEdge.WithProps fact : doc.facts) {
      if (fact.hasProperty(HypEdge.IS_X)) {
        //u.addEdgeToState(fact, Adjoints.Constant.ZERO);
        u.addEdgeToStateNoMatch(fact, Adjoints.Constant.ZERO);
        if (debug)
          System.out.println("[exFeats] x: " + fact);
        if (this.debug && fact.hasProperty(HypEdge.IS_DERIVED))
          System.out.println("derived state edge: " + fact);
        cx++;
      }
    }
    if (debug)
      Log.info("cx=" + cx + " cy=" + cy + " all=" + doc.facts.size());

    // Put out a notification that all of the annotations have been added.
    // Up to this, most actions will be blocked.
    HypEdge d = u.makeEdge(doneAnnoRel, docidN);
    u.addEdgeToState(d, Adjoints.Constant.ZERO);

    if (this.debug)
      Log.info("done setup on " + docid);
    timer.stop("setupUbertsForDoc");
    return docid;
  }

  public static String i2s(int i) {
    return String.valueOf(i);
  }
  public static int s2i(Object s) {
    return Integer.parseInt((String) s);
  }
  public static Span getTargetFromXuePalmer(HypEdge e) {
    int ts = s2i(e.getTail(0).getValue());
    int te = s2i(e.getTail(1).getValue());
    return Span.getSpan(ts, te);
  }
  public static Span getTargetFromSrl4(HypEdge e) {
    int ts = s2i(e.getTail(0).getValue());
    int te = s2i(e.getTail(1).getValue());
    return Span.getSpan(ts, te);
  }
  public static Span getArgFromSrl4(HypEdge e) {
    int ss = s2i(e.getTail(3).getValue());
    int se = s2i(e.getTail(4).getValue());
    return Span.getSpan(ss, se);
  }


  public static void ctxHelper(TemplateContext ctx, Span t, Span s) {
    ctxHelper(ctx, t, s, null, null);
  }
  public static void ctxHelper(TemplateContext ctx, Span t, Span s, String f, String k) {
    assert t != null;
    ctx.setTarget(t);
    ctx.setSpan2(t);
    if (f != null)
      ctx.setFrame(FrameIndex.getFrameWithSchemaPrefix(f));
    if (s != null) {
      ctx.setArg(s);
      ctx.setSpan1(s);
    }
    if (k != null)
      ctx.setRoleS(k);
    final HeadFinder hf = SemaforicHeadFinder.getInstance();
    Sentence sent = ctx.getSentence();
    assert sent != null;
    ctx.setTargetHead(hf.head(t, sent));
    ctx.setHead2(ctx.getTargetHead());
    if (s != null && s != Span.nullSpan) {
      ctx.setArgHead(hf.head(s, sent));
      ctx.setHead1(ctx.getArgHead());
    }
  }


  private static final String NS_START = i2s(Span.nullSpan.start);
  private static final String NS_END = i2s(Span.nullSpan.end);
  public static boolean isNullSpan(HypEdge srl4Edge) {
    assert srl4Edge.getRelation().getName().equals("srl4");
//    return s2i(srl4Edge.getTail(3).getValue()) == Span.nullSpan.start
//        && s2i(srl4Edge.getTail(4).getValue()) == Span.nullSpan.end;
    return srl4Edge.getTail(3).getValue().equals(NS_START)
        && srl4Edge.getTail(4).getValue().equals(NS_END);
  }

  /**
   * Calls {@link UbertsPipeline#consume(RelDoc)}
   */
  public void runInference(Iterator<RelDoc> x, String dataName) throws IOException {
    start(dataName);
    TimeMarker tm = new TimeMarker();
    int docs = 0;
    Iter itr = new Iter(x, typeInf, dontBackwardsGenerate);
    while (itr.hasNext()) {
      RelDoc doc = itr.next();
      docs++;
      setupUbertsForDoc(u, doc);
      consume(doc);
      if (tm.enoughTimePassed(15)) {
        Log.info("[main] dataName=" + dataName
            + " docsProcessed=" + docs
            + " time=" + tm.secondsSinceFirstMark());
      }
    }
    finish(dataName);
  }

  public static void main(String[] args) throws IOException {
    BrownClusters.DEBUG = true;
    ExperimentProperties config = ExperimentProperties.init(args);
    Log.info("starting...");
    File xyDefsFile = config.getExistingFile("relationDefs");
    List<File> schemaFiles = config.getExistingFiles("schemaFiles");
    File grammarFile = config.getExistingFile("grammarFile");
    Uberts u = new Uberts(new Random(9001), (edge, score) -> score.forwards());
    UbertsPipeline srl;
    String mode = config.getString("mode");
    if (mode.equals("learn")) {
      srl = new UbertsLearnPipeline(u, grammarFile, schemaFiles, xyDefsFile);
    } else if (mode.equals("features")) {
      Log.info("mimicing output of FeaturePrecomputation");
      UbertsExtractFeatsPipeline ef = new UbertsExtractFeatsPipeline(u, grammarFile, schemaFiles, xyDefsFile);
      ef.setFeaturePrecomputationMode(
          config.getFile("outputTemplateFeatureAlph"),
          config.getFile("outputRoleAlph"),
          config.getFile("outputFeatures"));
      srl = ef;
    } else {
      throw new RuntimeException("unknown mode: " + mode);
    }

//    Shard dataShard = config.getShard();
    boolean includeProvidence = false;
    boolean dedupInputLines = true;
    File multiXY = config.getExistingFile("inputRel");
    try (RelationFileIterator itr = new RelationFileIterator(multiXY, includeProvidence);
        ManyDocRelationFileIterator x  = new ManyDocRelationFileIterator(itr, dedupInputLines)) {
//      srl.runInference(x, dataShard);
      srl.runInference(x, "file:" + multiXY.getPath());
    }

    Log.info(Describe.memoryUsage());
    Log.info("done");
  }
}
