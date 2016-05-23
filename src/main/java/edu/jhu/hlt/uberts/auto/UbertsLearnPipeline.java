package edu.jhu.hlt.uberts.auto;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.BiConsumer;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.TemplateContext;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.HypEdge.WithProps;
import edu.jhu.hlt.uberts.Relation.EqualityArray;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorBackwardsParser.Iter;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.hlt.uberts.features.OldFeaturesWrapper;
import edu.jhu.hlt.uberts.features.WeightAdjoints;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.prim.tuple.Pair;

public class UbertsLearnPipeline extends UbertsPipeline {
    private BasicFeatureTemplates bft;
    private OldFeaturesWrapper.Ints feFast;

    public UbertsLearnPipeline(Uberts u, File grammarFile, Iterable<File> schemaFiles, File relationDefs) throws IOException {
      super(u, grammarFile, schemaFiles, relationDefs);
      ExperimentProperties config = ExperimentProperties.getInstance();
      bft = new BasicFeatureTemplates();
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
//      File bialph = new File("data/mimic-coe/framenet/coherent-shards/alphabet.txt.gz");
//      File fcounts = new File("data/mimic-coe/framenet/feature-counts/all.txt.gz");
      File bialph = config.getExistingFile("bialph");
      File fcounts = config.getExistingFile("featureCounts");
      feFast = new OldFeaturesWrapper.Ints(new OldFeaturesWrapper(bft, ts, bialph, fcounts), numBits);
      feFast.cacheAdjointsForwards = true;
      assert feFast.getInner() != null;
    }

    @Override
    public FeatureExtractionFactor<?> getScoreFor(Rule r) {
      return feFast;
    }

    @Override
    public void consume(RelDoc doc) {
      /*
       * I need to change the train method to work through just looking at
       * consume(doc).
       * To tell whether we are in a train/dev/test file, I need to maintain
       * a state machine. Check file name in start/finish.
       */
      throw new RuntimeException("fix implementation");
    }

    public void train(ExperimentProperties config) throws IOException {
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
  }