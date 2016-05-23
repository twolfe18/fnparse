package edu.jhu.hlt.uberts.auto;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.data.BrownClusters;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Labels;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Step;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorBackwardsParser.Iter;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser.TG;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.hlt.uberts.features.Weight;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.prim.tuple.Pair;
import edu.stanford.nlp.util.StringUtils;

/**
 * A super-class for implementing functionality which needs to scan over a
 * multi-rel file. Implements some common stuff around rule parsing, type
 * inference, and other setup.
 *
 * @author travis
 */
public abstract class UbertsPipeline {

  protected Uberts u;
  protected List<Rule> rules;
  protected List<Relation> helperRelations;
  protected TypeInference typeInf;

  // Both of these are single arg relations and their argument is a doc id.
  protected NodeType docidNT;
  protected Relation startDocRel;
  protected Relation doneAnnoRel;

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
    timer.put("adHocSrlClassificationByRole.setup", new Timer("adHocSrlClassificationByRole.setup", 30, true));
    timer.put("adHocSrlClassificationByRole.prediction", new Timer("adHocSrlClassificationByRole.prediction", 30, true));
    timer.put("adHocSrlClassificationByRole.update", new Timer("adHocSrlClassificationByRole.update", 30, true));

    rules = new ArrayList<>();
    helperRelations = new ArrayList<>();

    // succTok(i,j)
    helperRelations.add(u.addSuccTok(1000));

    startDocRel = u.readRelData("def startDoc <docid>");
    doneAnnoRel = u.readRelData("def doneAnno <docid>");
    docidNT = doneAnnoRel.getTypeForArg(0);

    // Read in the defs file which defines (and creates w.r.t. Uberts) the
    // relations which will appear in the values file.
    Log.info("reading in relation/node type definitions...");
    u.readRelData(relationDefs);

    Log.info("reading schema files...");
    for (File f : schemaFiles) {
      List<Relation> schemaRelations = u.readRelData(f);
      Log.info("read " + schemaRelations.size() + " schema relations from " + f.getPath());
      helperRelations.addAll(schemaRelations);
    }

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

  public abstract FeatureExtractionFactor<?> getScoreFor(Rule r);

  public abstract void consume(RelDoc doc);

  public void start(ManyDocRelationFileIterator x) {
    File prov = x.getWrapped().getFile();
    Log.info("starting on " + prov.getPath());
  }

  public void finish(ManyDocRelationFileIterator x) {
    File prov = x.getWrapped().getFile();
    Log.info("finished with " + prov.getPath());
  }

  private void addRule(Rule r) {
    rules.add(r);
    FeatureExtractionFactor<?> phi = getScoreFor(r);
    // Add to Uberts as a TransitionGenerator
    // Create all orderings of this rule
    for (Rule rr : Rule.allLhsOrders(r)) {
      TransitionGeneratorForwardsParser tgfp = new TransitionGeneratorForwardsParser();
      Pair<List<TKey>, TG> tg = tgfp.parse2(rr, u);

      Log.info("adding: " + rr);
      System.out.println(StringUtils.join(tg.get1(), "\n"));

      tg.get2().feats = phi;
      dbgTransitionGenerators.add(tg.get2());
      u.addTransitionGenerator(tg.get1(), tg.get2());
    }
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


  public void evaluate(ManyDocRelationFileIterator instances) {
    Iter itr = new Iter(instances, typeInf, Arrays.asList("succTok"));
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
    u.addEdgeToState(u.makeEdge(startDocRel, docidN));

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
        u.addEdgeToState(fact);
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
    u.addEdgeToState(d);

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

  public void runInference(ManyDocRelationFileIterator x) throws IOException {
    runInference(x, Shard.ONLY);
  }
  /**
   * Makes a pass over the data in the given iterator. Effects depend on
   * {@link Mode}.
   *
   * NOTE: Make sure you don't add any mutual exclusion factors (e.g. AtMost1)
   * before running this: we don't want to remove negative HypEdges which are
   * ruled out by choosing the right one first.
   *
   * @param dataShard may be null, meaning take all data.
   */
  public void runInference(ManyDocRelationFileIterator x, Shard dataShard) throws IOException {
    start(x);
    Log.info("dataShard=" + dataShard);
    ExperimentProperties config = ExperimentProperties.getInstance();
    TimeMarker tm = new TimeMarker();
    int docs = 0;
    int skippedDocs = 0;
    int maxInstances = config.getInt("maxInstances", 0);
    Iter itr = new Iter(x, typeInf, Arrays.asList("succTok"));
    while (itr.hasNext()) {
      RelDoc doc = itr.next();
      if (dataShard != null && !dataShard.matches(doc.getId())) {
        skippedDocs++;
        continue;
      }
      docs++;
      if (maxInstances > 0 && docs >= maxInstances) {
        Log.info("exiting early since we hit maxInstances=" + maxInstances);
        break;
      }
      setupUbertsForDoc(u, doc);
      consume(doc);
      if (tm.enoughTimePassed(15)) {
        Log.info("docsProcessed=" + docs + " docsSkipped=" + skippedDocs
            + " docsTotal=" + (docs+skippedDocs) + " time=" + tm.secondsSinceFirstMark());
      }
    }
    finish(x);
  }

  public static void main(String[] args) throws IOException {
    BrownClusters.DEBUG = true;
    ExperimentProperties config = ExperimentProperties.init(args);
    Log.info("starting...");
    File xyDefsFile = config.getExistingFile("relationDefs");
    List<File> schemaFiles = config.getExistingFiles("schemaFiles");
    File grammarFile = config.getExistingFile("grammarFile");
    Uberts u = new Uberts(new Random(9001));
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

    Shard dataShard = config.getShard();
    boolean includeProvidence = false;
    boolean dedupInputLines = true;
    File multiXY = config.getExistingFile("inputRel");
    try (RelationFileIterator itr = new RelationFileIterator(multiXY, includeProvidence);
        ManyDocRelationFileIterator x  = new ManyDocRelationFileIterator(itr, dedupInputLines)) {
      srl.runInference(x, dataShard);
    }

    Log.info(Describe.memoryUsage());
    Log.info("done");
  }
}
