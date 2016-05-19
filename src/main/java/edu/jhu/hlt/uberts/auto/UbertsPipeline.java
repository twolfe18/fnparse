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
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.TemplateContext;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Target;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.TemplateAlphabet;
import edu.jhu.hlt.fnparse.features.precompute.FeatureSet;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.data.BrownClusters;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
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
import edu.jhu.hlt.uberts.features.WeightList;
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
  private OldFeaturesWrapper.Strings feSlow;
  private OldFeaturesWrapper.Ints feFast;
  private Mode mode;

  // Both of these are single arg relations and their argument is a doc id.
  private NodeType docidNT;
  private Relation startDocRel;
  private Relation doneAnnoRel;

  public boolean debug = false;
  public MultiTimer timer;

  private static String prependRefinementTemplate(String refinement, File featureSet) {
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

  public UbertsPipeline(
      Uberts u,
      File grammarFile,
      Iterable<File> schemaFiles,
      File xyDefsFile) throws IOException {
    this.u = u;
    bft = new BasicFeatureTemplates();

    timer = new MultiTimer();
    timer.put("setupUbertsForDoc", new Timer("setupUbertsForDoc", 30, true));
    timer.put("adHocSrlClassificationByRole.setup", new Timer("adHocSrlClassificationByRole.setup", 30, true));
    timer.put("adHocSrlClassificationByRole.prediction", new Timer("adHocSrlClassificationByRole.prediction", 30, true));
    timer.put("adHocSrlClassificationByRole.update", new Timer("adHocSrlClassificationByRole.update", 30, true));

    ExperimentProperties config = ExperimentProperties.getInstance();
    mode = Mode.valueOf(config.getString("mode"));
    if (mode == Mode.EXTRACT_FEATS) {
      pNegSkip = config.getDouble("pNegSkip", 0.75);
      feSlow = new OldFeaturesWrapper.Strings(new OldFeaturesWrapper(bft), pNegSkip);
      feSlow.cacheAdjointsForwards = false;
    } else if (mode == Mode.LEARN) {
      int numBits = config.getInt("numBits", 20);

//      File fs = new File("data/srl-reldata/feature-sets/framenet/MAP/pos-320-16_neg-320-16.fs");

//      File fs = new File("data/srl-reldata/feature-sets/framenet/MAP/pos-1280-16_neg-1280-16.fs");
//      String ts = FeatureSet.getFeatureSetString(fs);

      // This really does work better than the others tried below :)
      // More features != better if you don't choose carefully.
      String ts = "";
      if (!"none".equals(config.getString("posFsFile", "none"))) {
        File posFsFile = config.getExistingFile("posFsFile");
        ts += prependRefinementTemplate("roleArg", posFsFile);
      }
      if (!"none".equals(config.getString("negFsFile", "none"))) {
        File negFsFile = config.getExistingFile("negFsFile");
        if (ts.length() > 0)
          ts += " + ";
        ts += prependRefinementTemplate("arg", negFsFile);
      }
      assert ts.length() > 0;

//      String ts = prependRefinementTemplate("arg", negFsFile)
//          + " + " + prependRefinementTemplate("argAndRoleArg", posFsFile);

//      String ts = prependRefinementTemplate("arg", negFsFile)
//          + " + " + prependRefinementTemplate("roleArg", posFsFile)
//          + " + " + prependRefinementTemplate("arg", posFsFile)
//          + " + " + prependRefinementTemplate("roleArg", negFsFile);


//      File bialph = new File("data/mimic-coe/framenet/coherent-shards/alphabet.txt.gz");
//      File fcounts = new File("data/mimic-coe/framenet/feature-counts/all.txt.gz");
      File bialph = config.getExistingFile("bialph");
      File fcounts = config.getExistingFile("featureCounts");

      feFast = new OldFeaturesWrapper.Ints(new OldFeaturesWrapper(bft, ts, bialph, fcounts), numBits);
      feFast.cacheAdjointsForwards = true;
      assert feFast.getInner() != null;
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

    if (mode == Mode.EXTRACT_FEATS) {
//      tg.get2().feats = this.feFast;
      tg.get2().feats = this.feSlow;
    } else {
      tg.get2().feats = new FeatureExtractionFactor.Oracle(r.rhs.relName);
    }

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

//  public FPR adHocSrlClassificationBySpan(RelDoc doc, boolean learn) {
    // srl3'(s3,s2,e2,k)
    // & event1'(e1,ts,te)
    // & event2'(e2,e1,f)
    // & srl1'(s1,ss,se)
    // & srl2'(s2,s1,e1)
    // => srl4(ts,te,f,ss,se,k)

    // For each gold and predicted can be Set<HypEdge> with types srl3 and srl4

    // Build t -> [s] index from xue-palmer-args
    // for each given gold event2, join with t->[s] to get [s]
    // for each span, classify yes or no, presence of gold srl3 give you a label
    // 
//  }

//  public FPR adHocSrlClassificationBySpan(RelDoc doc, boolean learn) {
//    setupUbertsForDoc(u, doc);
//
//    State state = u.getState();
//    Agenda agenda = u.getAgenda();
//
//    final Relation xuePalmerArgs = u.getEdgeType("xue-palmer-args");
//    final Relation srl4 = u.getEdgeType("srl4");
//    feFast.getInner().customEdgeCtxSetup = (yx, ctx) -> {
//      HypEdge yhat = yx.get1();
//      final HeadFinder hf = SemaforicHeadFinder.getInstance();
//
//      int ts = s2i(yhat.getTail(0).getValue());
//      int te = s2i(yhat.getTail(1).getValue());
//      ctx.setTarget(Span.getSpan(ts, te));
//      ctx.setSpan2(ctx.getTarget());
//
//      int ss, se;
//      if (yhat.getRelation() == xuePalmerArgs) {
//        ss = s2i(yhat.getTail(2).getValue());
//        se = s2i(yhat.getTail(3).getValue());
//      } else if (yhat.getRelation() == srl4) {
//        ctx.setFrame(FrameIndex.getFrameWithSchemaPrefix((String) yhat.getTail(2).getValue()));
//        ss = s2i(yhat.getTail(3).getValue());
//        se = s2i(yhat.getTail(4).getValue());
//        ctx.setRoleS((String) yhat.getTail(5).getValue());
//      } else {
//        throw new RuntimeException();
//      }
//
//      ctx.setArg(Span.getSpan(ss, se));
//      ctx.setSpan1(ctx.getArg());
//      Sentence sent = ctx.getSentence();
//      assert sent != null;
//      ctx.setTargetHead(hf.head(ctx.getTarget(), sent));
//      ctx.setHead2(ctx.getTargetHead());
//      if (ctx.getArg() != Span.nullSpan) {
//        ctx.setArgHead(hf.head(ctx.getArg(), sent));
//        ctx.setHead1(ctx.getArgHead());
//      }
//    };
//
//    // 0) Figure out what the gold labels are
//    Set<HypEdge> goldTFS = new HashSet<>();
//    Set<HypEdge> goldTFSK = new HashSet<>();
//    final Map<Span, String> goldT2F = new HashMap<>();
//    for (HypEdge e : doc.match2FromFacts(srl4)) {
//      todo
//    }
//
//    // Use refinements to describe w_0 and w_f for step 1
//    feFast.getInner().customRefinements = e -> {
//      if (e.getRelation() == xuePalmerArgs) {
//        Span t = getTargetFromXuePalmer(e);
//        String frame = goldT2F.get(t);
//        return new int[] {0, Hash.hash(frame)};
//      } else {
//        return new int[] {0};
//      }
//    };
//
//    // 1) Classify what spans are args
//    //    (w_0 + w_f) * f(t,s)
//    for (LL<HypEdge> cur = state.match2(xuePalmerArgs); cur != null; cur = cur.next) {
//      Adjoints sc1 = feFast.score(cur.item, u);
//      if (sc1.forwards() > 0) {
//        // 2) Classify the role
//        //    w * f(t,f,s,k)
//      }
//      
//      
//      if (learn) {
//        boolean gold = goldTFS.contains(o)
//      }
//    }
//  }

//  private boolean xpEdgeInGoldParse(HypEdge xpe, Uberts u) {
//    
//  }

  // for each t:
  // f is known from the label
  // filter s by w_0 f(t,s), penalize FNs 30x more than FPs
  // filter s by w_f f(t,s), penalize FNs 3x more than FPs
  // argmax_k by w_1 f(t,f,s,k)

  // Refine them with an array of length one
  // 0 for w_0
  // 1 for w_1
  // 2 + abs(hash(f)) for w_f

  // For filtering, if training always let through a gold item
  Counts<String> fc = new Counts<>();
  public FPR adHocSrlClassificationByRoleWithFiltering(RelDoc doc, boolean learn) {
    boolean debug = true;
    fc.increment(learn ? "inst.train" : "inst.test");
    if (debug)
      System.out.println("starting on " + doc.getId());

    timer.start("filter-setup");
    setupUbertsForDoc(u, doc);

    Relation srl4 = u.getEdgeType("srl4");
    NodeType tokenIndex = u.lookupNodeType("tokenIndex", false);
    Relation role = u.getEdgeType("role");
    NodeType fNT = u.lookupNodeType("frame", false);
    NodeType roleNT = u.lookupNodeType("roleLabel", false);

    Set<HashableHypEdge> predicted = new HashSet<>();
    Set<HashableHypEdge> gold = new HashSet<>();
    Set<SpanPair> tsGold = new HashSet<>();
    Map<Span, String> goldT2F = new HashMap<>();
    List<HypEdge.WithProps> matching = doc.match2FromFacts(srl4);
    for (HypEdge e : matching) {
      gold.add(new HashableHypEdge(e));
//      if (debug)
//        System.out.println("gold: " + e);
      Span t = getTargetFromSrl4(e);
      Span s = getArgFromSrl4(e);
      tsGold.add(new SpanPair(t, s));
      String f = (String) e.getTail(2).getValue();
      Object old = goldT2F.put(t, f);
      assert old == null || f.equals(old);
    }

    Map<Span, LL<Span>> xpRaw = buildXuePalmerIndex(doc);

    BiConsumer<Pair<HypEdge, Uberts>, TemplateContext> srl4CtxSetup =
        feFast.getInner().customEdgeCtxSetup;
    timer.stop("filter-setup");

    boolean goldTS = false;
    for (Entry<Span, String> tf : goldT2F.entrySet()) {
      fc.increment("targets");
      Span t = tf.getKey();
      String f = tf.getValue();
//    for (Span t : xpRaw.keySet()) {
//      String f = goldT2F.get(t);

      List<String> roles = null;  // memo

      HypNode ts = u.lookupNode(tokenIndex, String.valueOf(t.start), true);
      HypNode te = u.lookupNode(tokenIndex, String.valueOf(t.end), true);
      HypNode frame = u.lookupNode(fNT, f, false);

      for (LL<Span> cur = xpRaw.get(t); cur != null; cur = cur.next) {
        fc.increment("args");

        timer.start("filter-stage1");
        Span s = cur.item;
        HypNode ss = u.lookupNode(tokenIndex, String.valueOf(s.start), true);
        HypNode se = u.lookupNode(tokenIndex, String.valueOf(s.end), true);

        // Dirty hack:
        feFast.getInner().customEdgeCtxSetup = (eu, ctx) -> {
          ctxHelper(ctx, t, s);
        };
//        feFast.getInner().customRefinements = e -> w0Ref;
        int[] fx = feFast.featuresNoRefine(null, u);
        Adjoints s1 = feFast.w0.score(fx, false);
//        Adjoints s1 = feFast.getWf("pre-" + f).score(fx, false);
//        Adjoints s1 = Adjoints.Constant.ONE;
        s1 = Adjoints.cacheIfNeeded(s1);
        boolean pred1 = s1.forwards() > 0;
        timer.stop("filter-stage1");
        if (learn) {
          goldTS = tsGold.contains(new SpanPair(t, s));
          if (goldTS && !pred1) {         // FN
            fc.increment("f1-fn");
            s1.backwards(-1.5);
          } else if (!goldTS && pred1) {  // FP
            fc.increment("f1-fp");
            s1.backwards(+1);
          } else if (!goldTS && !pred1) {
            fc.increment("f1-tn");
          } else {
            fc.increment("f1-tp");
          }
//          pred1 |= goldTS; // always let gold spans through while training.
          pred1 = goldTS;
        }
        if (pred1) {
          // STEP TWO
          timer.start("filter-stage2");
          Adjoints s2 = feFast.getWf(f).score(fx, false);
//          Adjoints s2 = feFast.getWf("const").score(fx, false);
          s2 = Adjoints.cacheIfNeeded(s2);
          boolean pred2 = s2.forwards() > 0;
          timer.stop("filter-stage2");
          if (learn) {
            if (goldTS && !pred2) {   // FN
              fc.increment("f2-fn");
              s2.backwards(-1);
            } else if (!goldTS && pred2) {                  // FP
              fc.increment("f2-fp");
              s2.backwards(+1);
            } else if (!goldTS && !pred2) {
              fc.increment("f2-tn");
            } else {
              fc.increment("f2-tp");
            }
//            pred2 |= goldTS; // always let gold spans through while training.
            pred2 = goldTS;
          }
          if (pred2) {
//            if (debug)
//              System.out.println("made it through two filters: t=" + t + " s=" + s);
            fc.increment("passed-two-filters");
            timer.start("filter-stage3");
            // STEP THREE
            if (roles == null) {
              roles = new ArrayList<>();
              for (LL<HypEdge> kcur = u.getState().match(0, role, frame); kcur != null; kcur = kcur.next)
                roles.add((String) kcur.item.getTail(1).getValue());
            }
            HypEdge bestKE = null;
            HypEdge goldKE = null;
            Adjoints bestK = null;
            Adjoints goldK = null;
            for (String k : roles) {
              fc.increment("roleargs");
              // Build an srl4
              HypNode kn = u.lookupNode(roleNT, k, true);
              HypNode[] tail = new HypNode[] {
                  ts, te, frame, ss, se, kn
              };
              HypEdge srl4E = u.makeEdge(srl4, tail);
              feFast.getInner().customEdgeCtxSetup = srl4CtxSetup;
              int[] fx3 = feFast.featuresNoRefine(srl4E, u);
              Adjoints sc3 = feFast.w1.score(fx3, false);
              sc3 = Adjoints.cacheIfNeeded(sc3);
//              if (debug)
//                System.out.println("score of " + srl4E + " " + sc3.forwards());
              if (bestK == null || sc3.forwards() > bestK.forwards()) {
                bestK = sc3;
                bestKE = srl4E;
              }
              if (u.getLabel(srl4E)) {
                assert goldK == null;
                goldK = sc3;
                goldKE = srl4E;
              }
            }
            bestK.forwards();
            timer.stop("filter-stage3");
            if (bestK.forwards() > 0) {
              fc.increment("f3-pass");
              HashableHypEdge hhe = new HashableHypEdge(bestKE);
              predicted.add(hhe);
              boolean g = u.getLabel(hhe);
              assert g == (goldK == bestK);
              // FP
              if (learn) {
                if (!g) {
                  fc.increment("f3-fp");
                  bestK.backwards(+1);
                  if (goldK != null)
                    goldK.backwards(-1);
                } else {
                  fc.increment("f3-tp");
                }
              }
            } else {
              fc.increment("f3-fail");
              // FN
              if (learn && goldK != null) {
                fc.increment("f3-fn");
                goldK.backwards(-1);
              }
            }
          }
        }
      }
    }
    System.out.println(fc);
    System.out.println(timer);
    feFast.getInner().customEdgeCtxSetup = srl4CtxSetup;
    return FPR.fromSets(gold, predicted);
  }

  private Map<Span, LL<Span>> buildXuePalmerIndex(RelDoc doc) {
    Relation xuePalmerArgs = u.getEdgeType("xue-palmer-args");
//    State state = u.getState();
    Map<Span, LL<Span>> xuePalmerIndex = new HashMap<>();
//    for (LL<HypEdge> cur = state.match2(xuePalmerArgs); cur != null; cur = cur.next) {
//      HypEdge e = cur.item;
    for (HypEdge.WithProps e : doc.match2FromFacts(xuePalmerArgs)) {
      assert e.getNumTails() == 4;
      int ts = s2i(e.getTail(0).getValue());
      int te = s2i(e.getTail(1).getValue());
      int ss = s2i(e.getTail(2).getValue());
      int se = s2i(e.getTail(3).getValue());
      Span key = Span.getSpan(ts, te);
      Span val = Span.getSpan(ss, se);
      xuePalmerIndex.put(key, new LL<>(val, xuePalmerIndex.get(key)));
    }
    return xuePalmerIndex;
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

//  public Map<Span, LL<Span>> filteredXuePalmer(RelDoc doc, Uberts u, boolean learn, List<HypEdge.WithProps> srlArgs) {
//    State state = u.getState();
//
//    Map<Span, String> t2s = null; // TODO from srlArgs
//    
//    feFast.getInner().customEdgeCtxSetup = (eu, ctx) -> {
//      // edge will be ???
//      HypEdge xpEdge = eu.get1();
//      Span t = getTargetFromXuePalmer(xpEdge);
//      String f = t2s.get(t);
//      ctx.setFrame(FrameIndex.getFrameWithSchemaPrefix(f));
//      // set t, s
//    };
//    
//    Relation xuePalmerArgs = u.getEdgeType("xue-palmer-args");
//    Map<Span, LL<Span>> xuePalmerIndex = new HashMap<>();
//    for (LL<HypEdge> cur = state.match2(xuePalmerArgs); cur != null; cur = cur.next) {
//      HypEdge e = cur.item;
//
//      // First see if we want to prune this edge
//      Adjoints sFilter = feFast.score(e, u);
//      if (sFilter.forwards() > 0 && !learn)
//        continue;
//      // TODO Update by whether (t,s) was in gold parse?
//
//      assert e.getNumTails() == 4;
//      int ts = s2i(e.getTail(0).getValue());
//      int te = s2i(e.getTail(1).getValue());
//      int ss = s2i(e.getTail(2).getValue());
//      int se = s2i(e.getTail(3).getValue());
//      Span key = Span.getSpan(ts, te);
//      Span val = Span.getSpan(ss, se);
//      xuePalmerIndex.put(key, new LL<>(val, xuePalmerIndex.get(key)));
//    }
//
//    return xuePalmerIndex;
//  }

  /** shim */
  public FPR adHocSrlClassificationByRole(RelDoc doc, boolean learn) {
    return adHocSrlClassificationByRoleOld(doc, learn);
//    return adHocSrlClassificationByRoleWithFiltering(doc, learn);
  }

  /**
   * Setup and run inference for left-to-right span-by-span role-classification
   * with no global features.
   *
   * for each role:
   *   argmax_{span \in xue-palmer-arg U {nullSpan}} score(t,f,k,s)
   *
   * @param learn should only be false on the test set when you don't want to
   * use progressive validation (which, sadly, is the standard thing to do).
   */
  public FPR adHocSrlClassificationByRoleOld(RelDoc doc, boolean learn) {
    boolean debug = false;
    if (debug)
      Log.info("starting...");

    setupUbertsForDoc(u, doc);
    timer.start("adHocSrlClassificationByRole.setup");
//    u.getState().clearNonSchema();
//    u.getAgenda().clear();
//    u.initLabels();
//    for (HypEdge.WithProps fact : doc.facts)
//      if (fact.hasProperty(HypEdge.IS_Y))
//        u.addLabel(fact);
//    // Add an edge to the state specifying that we are working on this document/sentence.
//    String docid = doc.def.tokens[1];
//    HypNode docidN = u.lookupNode(docidNT, docid, true);
//    u.addEdgeToState(u.makeEdge(startDocRel, docidN));
    if (debug)
      Log.info("doc's facts: " + doc.countFacts());

    State state = u.getState();
    Agenda agenda = u.getAgenda();

    // Agenda doesn't index edges by relation, so I'll get them from RelDoc instead.
    Relation srlArg = u.getEdgeType("srlArg");
    List<HypEdge.WithProps> srlArgs = doc.match2FromFacts(srlArg);
    if (debug)
      Log.info("found " + srlArgs.size() + " srlArgs");

    // Clear the agenda and add from scratch
    agenda.clear();

    // Build a t -> [s] from xue-palmer-edges
    Relation xuePalmerArgs = u.getEdgeType("xue-palmer-args");
    int na = 0;
    Map<Span, LL<Span>> xuePalmerIndex = new HashMap<>();
    for (LL<HypEdge> cur = state.match2(xuePalmerArgs); cur != null; cur = cur.next) {
      HypEdge e = cur.item;
      assert e.getNumTails() == 4;
      int ts = s2i(e.getTail(0).getValue());
      int te = s2i(e.getTail(1).getValue());
      int ss = s2i(e.getTail(2).getValue());
      int se = s2i(e.getTail(3).getValue());
      Span key = Span.getSpan(ts, te);
      Span val = Span.getSpan(ss, se);
      xuePalmerIndex.put(key, new LL<>(val, xuePalmerIndex.get(key)));
      na++;
    }
    if (debug)
      Log.info("xue-palmer-args has " + xuePalmerIndex.size() + " targets and " + na + " arguments");

    // This is the input to feature extraction
    final Relation srl4 = u.getEdgeType("srl4");
    feFast.getInner().customEdgeCtxSetup = (yx, ctx) -> {
      HypEdge yhat = yx.get1();
      assert yhat.getRelation() == srl4;
      final HeadFinder hf = SemaforicHeadFinder.getInstance();

      int ts = s2i(yhat.getTail(0).getValue());
      int te = s2i(yhat.getTail(1).getValue());
      ctx.setTarget(Span.getSpan(ts, te));
      ctx.setSpan2(ctx.getTarget());
      ctx.setFrame(FrameIndex.getFrameWithSchemaPrefix((String) yhat.getTail(2).getValue()));
      int ss = s2i(yhat.getTail(3).getValue());
      int se = s2i(yhat.getTail(4).getValue());
      ctx.setArg(Span.getSpan(ss, se));
      ctx.setSpan1(ctx.getArg());
      ctx.setRoleS((String) yhat.getTail(5).getValue());

      Sentence sent = ctx.getSentence();
      assert sent != null;
      ctx.setTargetHead(hf.head(ctx.getTarget(), sent));
      ctx.setHead2(ctx.getTargetHead());
      if (ctx.getArg() != Span.nullSpan) {
        ctx.setArgHead(hf.head(ctx.getArg(), sent));
        ctx.setHead1(ctx.getArgHead());
      }
    };
    timer.stop("adHocSrlClassificationByRole.setup");


    // Make predictions one srlArg/(t,f,k) at a time.
    timer.start("adHocSrlClassificationByRole.prediction");
    HashMap<HashableHypEdge, Adjoints> scores = new HashMap<>();  // score of every (t,f,k,s)
    HashSet<HashableHypEdge> predictions = new HashSet<>();
    NodeType tokenIndex = u.lookupNodeType("tokenIndex", false);
    for (HypEdge tfk : srlArgs) {
      assert tfk.getNumTails() == 3;
      EqualityArray e1 = (EqualityArray) tfk.getTail(0).getValue();
      assert e1.length() == 2;
      int ts = Integer.parseInt((String) e1.get(0));
      int te = Integer.parseInt((String) e1.get(1));
      Span key = Span.getSpan(ts, te);
      HypNode frame = tfk.getTail(1);
      HypNode role = tfk.getTail(2);
      if (debug)
        Log.info("predicting span for target=" + key + " "  + frame + " " + role);
      LL<Span> possible = xuePalmerIndex.get(key);
      possible = new LL<>(Span.nullSpan, possible);
      // Loop over every span for this target
      Pair<HypEdge, Adjoints> best = null;
      for (LL<Span> cur = possible; cur != null; cur = cur.next) {
        Span s = cur.item;
        if (debug)
          System.out.println("\tconsidering: " + s);
        HypNode[] tail = new HypNode[6];
        tail[0] = u.lookupNode(tokenIndex, i2s(ts), true);
        tail[1] = u.lookupNode(tokenIndex, i2s(te), true);
        tail[2] = frame;
        tail[3] = u.lookupNode(tokenIndex, i2s(s.start), true);
        tail[4] = u.lookupNode(tokenIndex, i2s(s.end), true);
        tail[5] = role;
        HypEdge yhat = u.makeEdge(srl4, tail);
        Adjoints a = feFast.score(yhat, u);
//        u.addEdgeToAgenda(yhat, a);
        if (best == null || a.forwards() > best.get2().forwards())
          best = new Pair<>(yhat, a);

        Object old = scores.put(new HashableHypEdge(yhat), a);
        assert old == null;

        if (debug) {
        WeightAdjoints<?> wa = (WeightAdjoints<?>) Adjoints.uncacheIfNeeded(a);
        System.out.println("\tfeatures extractor=" + feFast.getClass()
            + " n=" + wa.getFeatures().size());
//            + "\t" + StringUtils.trunc(wa.getFeatures().toString(), 250));
        }
      }
//      Pair<HypEdge, Adjoints> best = agenda.popBoth();
      HypEdge yhat = best.get1();
      agenda.clear();

      // TODO: Create dynamic intercept, right now using score(noArg) = 0
      if (!(s2i(yhat.getTail(3).getValue()) == Span.nullSpan.start
          && s2i(yhat.getTail(4).getValue()) == Span.nullSpan.end)) {
        predictions.add(new HashableHypEdge(yhat));
      }
    }
    timer.stop("adHocSrlClassificationByRole.prediction");

    // Construct gold and hyp set for evaluation.
    // Perform updates.
    timer.start("adHocSrlClassificationByRole.update");
    HashSet<HashableHypEdge> gold = new HashSet<>();
    for (HypEdge e : doc.match2FromFacts(srl4)) {
      HashableHypEdge hhe = new HashableHypEdge(e);
      if (!gold.add(hhe)) {
        Log.warn("dup? " + e);
        continue;
      }
      if (learn && !predictions.contains(hhe)) {
        Adjoints fn = scores.get(hhe);
        if (fn != null)   // could be xue-palmer recall error
          fn.backwards(-1);
      }
    }
    if (learn) {
      for (HashableHypEdge hhe : predictions) {
        if (!gold.contains(hhe)) {
          Adjoints fp = scores.get(hhe);
          fp.backwards(+1);
        }
      }
      feFast.completedObservation();
    }
    timer.stop("adHocSrlClassificationByRole.update");

    FPR perf = FPR.fromSets(gold, predictions);
    if (debug) {
      Log.info("gold: " + gold);
      Log.info("hyp:  " + predictions);
      Log.info("perf: " + perf);
    }
    return perf;
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
   * Extracts features off of all actions generated from the transition grammar
   * and the x rel data given.
   *
   * NOTE: Make sure you don't add any mutual exclusion factors (e.g. AtMost1)
   * before running this: we don't want to remove negative HypEdges which are
   * ruled out by choosing the right one first.
   *
   * @param dataShard may be null, meaning take all data.
   */
  public void runInference(ManyDocRelationFileIterator x, File output, Shard dataShard) throws IOException {
    Log.info("writing features to " + output);
    Log.info("pSkipNeg=" + pNegSkip);
    Log.info("mode=" + mode);
    Log.info("dataShard=" + dataShard);
    ExperimentProperties config = ExperimentProperties.getInstance();

    // This will initialize the Alphabet over frame/role names and provide
    // the code to write out features.txt.gz and template-feat-indices.txt.gz
    FeaturePrecomputation.AlphWrapper fpre = null;
    if (mode == Mode.EXTRACT_FEATS && fPreRoleNameAlph != null) {
      boolean roleMode = true;
      fpre = new FeaturePrecomputation.AlphWrapper(roleMode, feSlow.getInner().getFeatures());
    }

    Timer trajProcessingTimer = new Timer("trajProcessingTimer", 10, true);
    TimeMarker tm = new TimeMarker();
    Counts<String> posRel = new Counts<>(), negRel = new Counts<>();
    int docs = 0, actions = 0;
    int skippedDocs = 0;
    long features = 0;

    int maxInstances = config.getInt("maxInstances", 0);
    Iter itr = new Iter(x, typeInf, Arrays.asList("succTok"));
    try (BufferedWriter w = FileUtil.getWriter(output)) {
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

          @SuppressWarnings("unchecked")
          WeightList<Pair<TemplateAlphabet, String>> fx = (WeightList<Pair<TemplateAlphabet, String>>) t.score;
//          WeightAdjoints<Pair<TemplateAlphabet, String>> fx =
//          (WeightAdjoints<Pair<TemplateAlphabet, String>>) t.score;
//          if (fx.getFeatures() == feSlow.SKIP) {
//            skipped++;
//            continue;
//          }
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
//              for (Pair<TemplateAlphabet, String> tf : fx.getFeatures()) {
              for (Weight<Pair<TemplateAlphabet, String>> wtf : fx) {
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

          if (y) posRel.increment(t.edge.getRelation().getName());
          else negRel.increment(t.edge.getRelation().getName());
//          if (debug)
//            System.out.println("[exFeats.orTraj] " + lab + " " + t.edge);// + " " + new HashableHypEdge(t.edge).hc);
          actions++;
        }
        trajProcessingTimer.stop();
        if (mode == Mode.LEARN) {
          Log.info("n=" + traj.size() + " model wrt weakOracle: " + fpr.toString());
        } else if (mode == Mode.EXTRACT_FEATS) {
          if (debug) {
            Log.info("skippedFeatures=" + skipped + " keptFeatures=" + kept
                + " skippedDocs=" + skippedDocs + " keptDocs=" + docs);
          }
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
          Log.info("docsKept=" + docs + " docsSkipped=" + skippedDocs + " totalDocs=" + (docs+skippedDocs));
          System.out.println();
          w.flush();
        }
      }
    }

    if (mode == Mode.EXTRACT_FEATS && fPreRoleNameAlph != null)
      fpre.saveAlphabets(fPreTemplateFeatureAlph, fPreRoleNameAlph);
  }

  public void train(ExperimentProperties config) throws IOException {

//    List<File> trainRels = config.getExistingFiles("relTrain");
    List<File> trainRels = config.getFileGlob("relTrain");
    File testRel = config.getExistingFile("relTest");
    Log.info("train=" + trainRels);
    Log.info("test=" + testRel);
    File devRel = null;
    if (config.containsKey("relDev")) {
      devRel = config.getExistingFile("relDev");
      Log.info("dev=" + devRel);
    } else {
      Log.info("taking every 10th exaple in train as dev");
    }

    boolean includeProvidence = false;
    boolean dedupInputLines = true;
    for (int i = 0; i < trainRels.size(); i++) {
      File trainRel = trainRels.get(i);
      Log.info("starting iter=" + i + ": " + trainRel.getPath());

      // TRAIN
      FPR trainPerf = new FPR();
      FPR trainPerfWindow = new FPR();
      try (RelationFileIterator itr = new RelationFileIterator(trainRel, includeProvidence);
          ManyDocRelationFileIterator x  = new ManyDocRelationFileIterator(itr, dedupInputLines)) {
        Iter it = new Iter(x, typeInf, Arrays.asList("succTok"));
        FPR devPerf = new FPR();
        int processed = 0;
        while (it.hasNext()) {
          RelDoc doc = it.next();
          if (devRel == null && processed % 10 == 0) {
            feFast.useAverageWeights(true);
            FPR perfDoc = adHocSrlClassificationByRole(doc, false);
            devPerf.accum(perfDoc);
            Log.info("iter=" + i + " processed=" + processed + " dev: "
                + devPerf + " cur(" + doc.getId() +"): " + perfDoc);
            feFast.useAverageWeights(false);
          } else {
            FPR perfDoc = adHocSrlClassificationByRole(doc, true);
            trainPerf.accum(perfDoc);
            trainPerfWindow.accum(perfDoc);
            Log.info("iter=" + i + " processed=" + processed + " train: "
                + trainPerf + " window: " + trainPerfWindow
                + " cur(" + doc.getId() +"): " + perfDoc);
          }
          processed++;
          if (processed % 500 == 0)
            trainPerfWindow = new FPR();
          if (processed % 30 == 0)
            Log.info(Describe.memoryUsage());
        }
      }

      // DEV
      if (devRel != null) {
        feFast.useAverageWeights(true);
        FPR devPerf = new FPR();
        try (RelationFileIterator itr = new RelationFileIterator(devRel, includeProvidence);
            ManyDocRelationFileIterator x  = new ManyDocRelationFileIterator(itr, dedupInputLines)) {
          int processed = 0;
          Iter it = new Iter(x, typeInf, Arrays.asList("succTok"));
          while (it.hasNext()) {
            RelDoc doc = it.next();
            FPR perfDoc = adHocSrlClassificationByRole(doc, false);
            devPerf.accum(perfDoc);
            processed++;
            Log.info("iter=" + i + " processed=" + processed + " dev: "
                + devPerf + " cur(" + doc.getId() +"): " + perfDoc);
            if (processed % 30 == 0)
              Log.info(Describe.memoryUsage());
          }
        }
        feFast.useAverageWeights(false);
      }

      // TEST
      FPR testPerf = new FPR();
      try (RelationFileIterator itr = new RelationFileIterator(testRel, includeProvidence);
          ManyDocRelationFileIterator x  = new ManyDocRelationFileIterator(itr, dedupInputLines)) {
        feFast.useAverageWeights(true);
        int processed = 0;
        Iter it = new Iter(x, typeInf, Arrays.asList("succTok"));
        while (it.hasNext()) {
          RelDoc doc = it.next();
          FPR perfDoc = adHocSrlClassificationByRole(doc, false);
          testPerf.accum(perfDoc);
          processed++;
          Log.info("iter=" + i + " processed=" + processed + " test: "
              + trainPerf + " cur(" + doc.getId() +"): " + perfDoc);
          if (processed % 30 == 0)
            Log.info(Describe.memoryUsage());
        }
        feFast.useAverageWeights(false);
      }

    }
  }

  public static void main(String[] args) throws IOException {
    BrownClusters.DEBUG = true;
    ExperimentProperties config = ExperimentProperties.init(args);
    Log.info("starting...");

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

    if (srl.mode == Mode.LEARN) {
      srl.train(config);
    } else {

//    File multiXY = new File("data/srl-reldata/propbank/instances.rel.multi.gz");
//    File multiYhat = new File("data/srl-reldata/propbank/instances.yhat.rel.multi.gz");
      File multiXY = config.getExistingFile("inputRel");
      File multiYhat = config.getFile("outputRel");

      try (RelationFileIterator itr = new RelationFileIterator(multiXY, includeProvidence);
          ManyDocRelationFileIterator x  = new ManyDocRelationFileIterator(itr, dedupInputLines)) {
        srl.runInference(x, multiYhat, dataShard);
      }
    }

    Log.info(Describe.memoryUsage());
    Log.info("done");
  }
}
