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

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Target;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.TemplateAlphabet;
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
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.Timer;
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
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.hlt.uberts.srl.Srl3EdgeWrapper;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.HPair;

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
      feSlow = new OldFeaturesWrapper.Strings(new OldFeaturesWrapper(bft), pNegSkip);
      feSlow.cacheAdjointsForwards = false;
    } else if (mode == Mode.LEARN) {
      int numBits = 20;
      feFast = new OldFeaturesWrapper.Ints(new OldFeaturesWrapper(bft), numBits);
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
    assert doc.items.isEmpty() && !doc.facts.isEmpty();

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
    return String.valueOf(i).intern();
  }
  public static int s2i(Object s) {
    return Integer.parseInt((String) s);
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
  public FPR adHocSrlClassificationByRole(RelDoc doc, boolean learn) {
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
        u.addEdgeToAgenda(yhat, a);

        Object old = scores.put(new HashableHypEdge(yhat), a);
        assert old == null;

        if (debug) {
        WeightAdjoints<?> wa = (WeightAdjoints<?>) Adjoints.uncacheIfNeeded(a);
        System.out.println("\tfeatures extractor=" + feFast.getClass()
            + " n=" + wa.getFeatures().size());
//            + "\t" + StringUtils.trunc(wa.getFeatures().toString(), 250));
        }
      }
      Pair<HypEdge, Adjoints> best = agenda.popBoth();
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

  private static final String NS_START = i2s(Span.nullSpan.start).intern();
  private static final String NS_END = i2s(Span.nullSpan.end).intern();
  public static boolean isNullSpan(HypEdge srl4Edge) {
    assert srl4Edge.getRelation().getName().equals("srl4");
//    return s2i(srl4Edge.getTail(3).getValue()) == Span.nullSpan.start
//        && s2i(srl4Edge.getTail(4).getValue()) == Span.nullSpan.end;
    return srl4Edge.getTail(3).getValue() == NS_START
        && srl4Edge.getTail(4).getValue() == NS_END;
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

    FPR trainPerf = new FPR();
    FPR devPerf = new FPR();
    FPR testPerf = new FPR();
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

//        // For testing base memory requirements for iterating over the data.
//        if (true) {
//          if (docs % 1000 == 0)
//            Log.info("docs=" + docs + " " + Describe.memoryUsage());
//          continue;
//        }

        if (maxInstances > 0 && docs >= maxInstances) {
          Log.info("exiting early since we hit maxInstances=" + maxInstances);
          break;
        }

        if (mode == Mode.LEARN) {// && u.getRandom().nextDouble() < 0.5) {
          if (u.getRandom().nextDouble() < 0.3) {
            feFast.useAverageWeights(true);
            FPR perfDoc = adHocSrlClassificationByRole(doc, false);
            testPerf.accum(perfDoc);
            feFast.useAverageWeights(false);
            Log.info("test(avg): " + testPerf + "   cur(" + doc.getId() +"): " + perfDoc);
          } else {
            FPR perfDoc = adHocSrlClassificationByRole(doc, true);
            trainPerf.accum(perfDoc);
            Log.info("train: " + trainPerf + "   cur(" + doc.getId() +"): " + perfDoc);
          }
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
            if (fx.getFeatures() == feSlow.SKIP) {
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

  public static void main(String[] args) throws IOException {
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
      srl.runInference(x, multiYhat, dataShard);
    }

    Log.info(Describe.memoryUsage());
    Log.info("done");
  }
}
