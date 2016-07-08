package edu.jhu.hlt.uberts.auto;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.TemplateContext;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.data.BrownClusters;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.DecisionFunction;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.factor.LocalFactor;
import edu.jhu.hlt.uberts.features.OldFeaturesWrapper;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;

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
  protected List<Relation> helperRelations;
  protected TypeInference typeInf;

  // Both of these are single arg relations and their argument is a doc id.
  protected NodeType docidNT;
  protected Relation startDocRel;   // startDoc(docid) is added when a new doc is considered, can be used as a message to dump caches
  protected Relation doneAnnoRel;   // doneAnno(docid) is added when all pos/ner/parses have been added, good time to trigger pushing actions onto the agenda who may inspect the graph for features

  // Keeps track of how frequently types of events happen
  protected Counts<String> eventCounts = new Counts<>();

  // When running TypeInference.Expander, for which relations should we not
  // generate facts which are implied by the label (e.g. schema relations).
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

    dontBackwardsGenerate = new HashSet<>();
    dontBackwardsGenerate.add("succTok");

    rules = new ArrayList<>();
    helperRelations = new ArrayList<>();

    // succTok(i,j)
//    helperRelations.add(u.addSuccTok(1000));

    // Read in the defs file which defines (and creates w.r.t. Uberts) the
    // relations which will appear in the values file.
    if (DEBUG > 0)
      Log.info("reading in relation/node type definitions...");
    u.readRelData(relationDefs);

//    startDocRel = u.readRelData("def startDoc <docid>");
//    doneAnnoRel = u.readRelData("def doneAnno <docid>");
    startDocRel = u.getEdgeType("startDoc", true);
    doneAnnoRel = u.getEdgeType("doneAnno", true);
    if (startDocRel == null || doneAnnoRel == null) {
      throw new RuntimeException("make sure startDoc and doneAnno have been "
          + "defined in the relations file (" + relationDefs.getPath() + ")");
    }
    docidNT = doneAnnoRel.getTypeForArg(0);
    assert docidNT == startDocRel.getTypeForArg(0);

    Log.info("reading schema files...");
    for (File f : schemaFiles) {
      List<Relation> schemaRelations = u.readRelData(f);
      if (DEBUG > 0)
        Log.info("read " + schemaRelations.size() + " schema relations from " + f.getPath());
      helperRelations.addAll(schemaRelations);
      for (Relation r : schemaRelations)
        dontBackwardsGenerate.add(r.getName());
    }

    Log.info("[main] grammarFile=" + grammarFile.getPath());
    if (DEBUG > 0)
      Log.info("running type inference...");
    this.typeInf = new TypeInference(u);
    for (Rule untypedRule : Rule.parseRules(grammarFile, null))
      typeInf.add(untypedRule);

    List<Rule> typedRules = typeInf.runTypeInference();
    List<Rule> temp = new ArrayList<>();
    for (Rule typedRule : typedRules) {
      if (typedRule.comment != null && typedRule.comment.toLowerCase().contains("nopredict")) {
        if (DEBUG > 0)
          Log.info("not including in transition system because of NOPREDICT comment: " + typedRule);
        continue;
      }
      temp.add(typedRule);
    }
    typedRules = temp;
    temp = null;

    for (Rule typedRule : typedRules)
      addRule(typedRule);

    ExperimentProperties config = ExperimentProperties.getInstance();

    // TODO Re-visit whether this is necessary, what the correct value should be
    double lhsInRhsScoreScale = config.getDouble("lhsInRhsScoreScale", 0);
    Log.info("[main] lhsInRhsScoreScale=" + lhsInRhsScoreScale);
    assert lhsInRhsScoreScale >= 0;
    if (lhsInRhsScoreScale > 0) {
      throw new RuntimeException("update TransitionGenerator/TG code to TransGen");
    }

    // E.g. "EXACTLY_ONE:argument4(t,f,s,k):t:k"
    String dfs = config.getString("byGroupDecoder", "");
    if (dfs.isEmpty()) {
      u.setThresh(new DecisionFunction.DispatchByRelation());
    } else {
      u.setThresh(DecisionFunction.DispatchByRelation.parseMany(dfs, u));
    }
//    if (!dfs.isEmpty()) {
//      DecisionFunction df = DecisionFunction.ByGroup.parseMany(dfs, u);
//      u.prependDecisionFunction(df);
//    }

    // Thresholds for each each relation
    // E.g. "srl2=-3 srl3=-3"
    String rts = config.getString("threshold", "");
    if (!rts.isEmpty()) {
      throw new RuntimeException("should you really be using a threshold? need to re-implement");
//      for (String t : rts.split("\\s+")) {
//        String[] rt = t.split("=");
//        assert rt.length == 2;
//        Relation r = u.getEdgeType(rt[0]);
//        double thresh = Double.parseDouble(rt[1]);
//        u.prependDecisionFunction(new DecisionFunction.Constant(r, thresh));
//      }
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
    LocalFactor phi = getScoreFor(r);
    u.addTransitionGenerator(r, phi);
  }

  public void addRelData(File xyValuesFile) throws IOException {
    // Read in the x,y data
    Log.info("reading in x,y data...");
    try (BufferedReader r = FileUtil.getReader(xyValuesFile)) {
      u.readRelData(r);
    }
    System.out.println();
  }

  public void cleanupUbertsForDoc(Uberts u, RelDoc doc) {
    timer.start("cleanupUbertsForDoc");
    if (DEBUG > 1)
      System.out.println("[cleanupUbertsForDoc] " + doc.getId());
    u.dbgSentenceCache = null;

    // It is certainly impossible to never call this or do something to clean
    // up the set of interned HypNodes, most obviously because of head nodes.
    // Unless you're predictions are pretty simple, the number of distinct
    // HypEdges, and therefore head HypNodes, should grow with the data set.
    // For something like Ontonotes 5, this can get too big to stream over with
    // a reasonable amount of memory (~4GB).
    u.clearNonSchemaNodes();

    u.clearLabels();
    timer.stop("cleanupUbertsForDoc");
  }

  /**
   * OPTIONAL, call after setupUbertsForDoc (say, at the start of consume) if
   * needed. Sets {@link Uberts#dbgSentenceCache}
   */
  protected void buildSentenceCacheInUberts() {
    // Build Sentence, DependencyParses, and ConsituencyParses
    assert u.dbgSentenceCache == null;
    u.dbgSentenceCache = OldFeaturesWrapper.readSentenceFromState(u);
  }

  /**
   * @return the doc id.
   */
  public String setupUbertsForDoc(Uberts u, RelDoc doc) {
    boolean debug = false;
    if (debug)
      System.out.println("[setupUbertsForDoc] " + doc.getId());

//    u.getAgenda().clear();
    u.clearAgenda();
    u.initLabels();

    // Add an edge to the state specifying that we are working on this document/sentence.
    String docid = doc.def.tokens[1];
    HypNode docidN = u.lookupNode(docidNT, docid, true /* addIfNotPresent */, false /* isSchema */);
    u.addEdgeToState(u.makeEdge(false /* isSchema */, startDocRel, docidN), Adjoints.Constant.ZERO);

    int cx = 0, cy = 0;
    assert doc.items.isEmpty();
    assert !doc.facts.isEmpty() : "facts: " + doc.facts;

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
    HypEdge d = u.makeEdge(false /* isSchema */, doneAnnoRel, docidN);
    u.addEdgeToState(d, Adjoints.Constant.ZERO);

    if (this.debug)
      Log.info("done setup on " + docid);
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
   *
   * @param x only needs to be raw data (not expanded w.r.t. a transition grammar,
   * like {@link TypeInference.Expander})
   */
  public void runInference(Iterator<RelDoc> x, String dataName) throws IOException {
    start(dataName);
    TimeMarker tm = new TimeMarker();
    int docs = 0;

    int interval = 500;
    Timer tIter = new Timer("UbertsPipeline.IO", interval, true);
    Timer tExp = new Timer("UbertsPipeline.TypeInf.Expand", interval, true);
    Timer tSetup = new Timer("UbertsPipeline.Setup", interval, true);
    Timer tConsume = new Timer("UbertsPipeline.Consume", interval, true);
    Timer tCleanup = new Timer("UbertsPipeline.Cleanup", interval, true);

    // NOTE: This iterator calls lookupNode which makes Uberts grow in memory
    // usage. See Uberts.clearNodes, which is called from cleanupUbertsForDoc.
    TypeInference.Expander exp = typeInf.new Expander(dontBackwardsGenerate);
    while (x.hasNext()) {
      tIter.start();
      RelDoc doc = x.next();
      docs++;
      tIter.stop();

      // Generate intermediate facts w.r.t. the transition system
      tExp.start();
      exp.expand(doc);
      tExp.stop();

      // Add labels, input data to state, etc
      tSetup.start();
      setupUbertsForDoc(u, doc);
      tSetup.stop();

      // Call learning/prediction algorithm
      tConsume.start();
      consume(doc);
      tConsume.stop();

      // Free any non-schema edges and nodes
      tCleanup.start();
      cleanupUbertsForDoc(u, doc);
      tCleanup.stop();

      if (tm.enoughTimePassed(15)) {
        Log.info("[main] dataName=" + dataName
            + " docsProcessed=" + docs
            + " time=" + tm.secondsSinceFirstMark());
      }
    }
    finish(dataName);
  }

  /**
   * @deprecated see {@link UbertsLearnPipeline}
   */
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

    boolean includeProvidence = false;
    boolean dedupInputLines = true;
    File multiXY = config.getExistingFile("inputRel");
    try (RelationFileIterator itr = new RelationFileIterator(multiXY, includeProvidence);
        ManyDocRelationFileIterator x  = new ManyDocRelationFileIterator(itr, dedupInputLines)) {
      srl.runInference(x, "file:" + multiXY.getPath());
    }

    Log.info(Describe.memoryUsage());
    Log.info("done");
  }
}
