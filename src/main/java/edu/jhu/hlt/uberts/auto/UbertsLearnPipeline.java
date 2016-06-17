package edu.jhu.hlt.uberts.auto;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.google.common.collect.Iterators;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.OrderStatistics;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.AgendaPriority;
import edu.jhu.hlt.uberts.DecisionFunction;
import edu.jhu.hlt.uberts.DecisionFunction.ByGroup.ByGroupMode;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.Labels;
import edu.jhu.hlt.uberts.Labels.Perf;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Step;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.Uberts.AgendaSnapshot;
import edu.jhu.hlt.uberts.Uberts.Traj;
import edu.jhu.hlt.uberts.factor.GlobalFactor;
import edu.jhu.hlt.uberts.factor.LocalFactor;
import edu.jhu.hlt.uberts.factor.NumArgsRoleCoocArgLoc;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.hlt.uberts.features.OldFeaturesWrapper;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.prim.tuple.Pair;

public class UbertsLearnPipeline extends UbertsPipeline {
  public static int DEBUG = 1;  // 0 means off, 1 means coarse, 2+ means fine grain logging

  public static enum TrainMethod {
    EARLY_UPDATE,
    MAX_VIOLATION,
    DAGGER,
    DAGGER1,  // Only updates w.r.t. top item on the agenda at every state
  }

  // How should the document being consumed be interpretted?
  public static enum Mode {
    TRAIN, DEV, TEST,
  }

  static boolean performTest = false;

  static String[] oracleFeats = new String[] {};
  static boolean graphFeats = false;
  static boolean templateFeats = false;
  static boolean pipeline = false;
  static TrainMethod trainMethod = TrainMethod.DAGGER1;

  static boolean srl2ByArg = true;
  static boolean argument4ByArg = true;

  static boolean predicate2Mutex = true;
  static boolean enableGlobalFactors = true;

  static double costFP_srl3 = 1;
  static double costFP_srl2 = 1;
  static double costFP_event1 = 1;

  private Mode mode;

  private BasicFeatureTemplates bft;
  private OldFeaturesWrapper ofw;
  private OldFeaturesWrapper.Ints2 feFast2;
  private List<OldFeaturesWrapper.Ints3> feFast3;
  /** @deprecated */
  private OldFeaturesWrapper.Ints feFast;

  private List<Consumer<Double>> batch = new ArrayList<>();
  private int batchSize = 1;
  private boolean updateAccordingToPriority = false;
  private double pOracleRollIn = 0;

  private static boolean skipSrlFilterStages = false;

  private NumArgsRoleCoocArgLoc numArgsArg4;
  private NumArgsRoleCoocArgLoc numArgsArg3;
  private NumArgsRoleCoocArgLoc numArgsArg2;
  // These are not mutually exclusive, above are in below
  private List<GlobalFactor> globalFactors = new ArrayList<>();

  // For now these store all performances for the last data segment
  private List<Map<String, FPR>> perfByRel = new ArrayList<>();


  public static void main(String[] args) throws IOException {
    Log.info("[main] starting at " + new java.util.Date().toString());
    ExperimentProperties config = ExperimentProperties.init(args);

    // I'm tired of this popping up in an un-expected place, do it AOT.
//    FrameIndex.getFrameNet();
    FrameIndex.getPropbank();

    File grammarFile = config.getExistingFile("grammar");
    File relationDefs = config.getExistingFile("relations");
    List<File> schemaFiles = config.getExistingFiles("schema");

    performTest = config.getBoolean("performTest", performTest);
    Log.info("[main] performTest=" + performTest);

    skipSrlFilterStages = config.getBoolean("skipSrlFilterStages", skipSrlFilterStages);
    Log.info("[main] skipSrlFilterStages=" + skipSrlFilterStages);

    costFP_event1 = config.getDouble("costFP_event1", costFP_event1);
    costFP_srl2 = config.getDouble("costFP_srl2", costFP_srl2);
    costFP_srl3 = config.getDouble("costFP_srl3", costFP_srl3);
    Log.info("[main]"
        + " costFP_event1=" + costFP_event1
        + " costFP_srl2=" + costFP_srl2
        + " costFP_srl3=" + costFP_srl3);

    srl2ByArg = config.getBoolean("srl2ByArg", srl2ByArg);
    Log.info("[main] srl2ByArg=" + srl2ByArg);

    argument4ByArg = config.getBoolean("argument4ByArg", argument4ByArg);
    Log.info("[main] argument4ByArg=" + argument4ByArg);

    oracleFeats = config.getStrings("oracleFeats", oracleFeats);
    graphFeats = config.getBoolean("graphFeats", false);
    templateFeats = config.getBoolean("templateFeats", true);

    trainMethod = TrainMethod.valueOf(config.getString("trainMethod", trainMethod.toString()));
    Log.info("[main] trainMethod=" + trainMethod);

    predicate2Mutex = config.getBoolean("pred2Mutex", true);
    Log.info("predicate2Mutex=" + predicate2Mutex);

    String gfm = config.getString("globalFeatMode");
    Log.info("[main] globalFeatMode=" + gfm);
    switch (gfm.toLowerCase()) {
    case "none":
    case "off":
      enableGlobalFactors = false;
      NumArgsRoleCoocArgLoc.numArgs = false;
      NumArgsRoleCoocArgLoc.argLocPairwise = false;
      NumArgsRoleCoocArgLoc.argLocGlobal = false;
      NumArgsRoleCoocArgLoc.roleCooc = false;
      break;
    case "argloc":
      enableGlobalFactors = true;
      NumArgsRoleCoocArgLoc.numArgs = false;
      NumArgsRoleCoocArgLoc.argLocPairwise = true;
      NumArgsRoleCoocArgLoc.argLocGlobal = true;
      NumArgsRoleCoocArgLoc.roleCooc = false;
      break;
    case "arglocpairwise":
      enableGlobalFactors = true;
      NumArgsRoleCoocArgLoc.numArgs = false;
      NumArgsRoleCoocArgLoc.argLocPairwise = true;
      NumArgsRoleCoocArgLoc.argLocGlobal = false;
      NumArgsRoleCoocArgLoc.roleCooc = false;
      break;
    case "numarg":
    case "numargs":
      enableGlobalFactors = true;
      NumArgsRoleCoocArgLoc.numArgs = true;
      NumArgsRoleCoocArgLoc.argLocPairwise = false;
      NumArgsRoleCoocArgLoc.argLocGlobal = false;
      NumArgsRoleCoocArgLoc.roleCooc = false;
      break;
    case "rolecooc":
      enableGlobalFactors = true;
      NumArgsRoleCoocArgLoc.numArgs = false;
      NumArgsRoleCoocArgLoc.argLocPairwise = false;
      NumArgsRoleCoocArgLoc.argLocGlobal = false;
      NumArgsRoleCoocArgLoc.roleCooc = true;
      break;
    case "full":
    case "all":
      enableGlobalFactors = true;
      NumArgsRoleCoocArgLoc.numArgs = true;
      NumArgsRoleCoocArgLoc.argLocPairwise = true;
      NumArgsRoleCoocArgLoc.argLocGlobal = true;
      NumArgsRoleCoocArgLoc.roleCooc = true;
      break;
    default:
      throw new RuntimeException("unknown globalFeatMode: " + gfm);
    }

    // This is how many passes over ALL training files are given. If there are
    // 10 train files, each of which is all the data shuffled in a particular
    // way, then setting this to 3 would mean making 30 epochs.
    int passes = config.getInt("passes", 10);
    Log.info("[main] passes=" + passes);

    BiFunction<HypEdge, Adjoints, Double> agendaPriority =
        AgendaPriority.parse(config.getString("agendaPriority", "1*easyFirst + 1*dfs"));

    Uberts u = new Uberts(new Random(9001), agendaPriority);
    UbertsLearnPipeline pipe = new UbertsLearnPipeline(u, grammarFile, schemaFiles, relationDefs);

    /*
     * We set the threshold during inference very low, but when we update,
     * we check whether a score is above 0, lowering or raising it as need be.
     *
     * When I do this, I set costFP=1 for all Relations.
     */
//    u.setMinScore("srl2", -3);
//    u.setMinScore("srl3", -3);
    Relation srl2 = u.getEdgeType("srl2", true);
    if (srl2 == null) {
      Log.info("[main] no srl2 relation, assuming this is frame id");
    } else {
      u.prependDecisionFunction(new DecisionFunction.Constant(u.getEdgeType("srl2"), -3));
      u.prependDecisionFunction(new DecisionFunction.Constant(u.getEdgeType("srl3"), -3));

      u.prependDecisionFunction(new DecisionFunction.ByGroup(ByGroupMode.EXACTLY_ONE, "argument4(t,f,s,k):t:f:k", u));
    }
//    u.setMinScore("predicate2", Double.NEGATIVE_INFINITY);
//    u.setMinScore("predicate2", -1);

    // This is now a ExperimentProperties config string "AtLeast1Local" -> "predicate2(t,f):t"
//    u.preAgendaAddMapper = new AtLeast1Local("predicate2(t,f):t", u);
    if (!config.containsKey("AtLeast1Local"))
      Log.info("WARNING: did you forget to set AtLeast1Local=\"predicate2(t,f):t\"");

    // Train and dev should be shuffled. Test doesn't need to be.
    List<File> train = config.getExistingFiles("train.facts");
    File dev = config.getExistingFile("dev.facts");
    File test = performTest ? config.getExistingFile("test.facts") : null;
    Log.info("[main] trainFiles=" + train);
    Log.info("[main] devFile=" + dev.getPath());
    if (performTest)
      Log.info("[main] testFile=" + test.getPath());

    // The ratio of miniDevSize/trainSegSize is the price of knowing how well
    // you're doing during training. Try to minimize it to keep train times down.
    int miniDevSize = config.getInt("miniDevSize", 300);
    int trainSegSize = config.getInt("trainSegSize", miniDevSize * 20);


    /*
     * for each pass over all train data:
     *    for each segment of size N:
     *       train(train-segment)
     *       eval(dev-small)  # can be the first N documents in the dev set
     *    eval(dev)
     *    eval(test)
     */
    for (int i = 0; i < passes; i++) {
      for (File f : train) {
        Log.info("[main] pass=" + (i+1) + " of=" + passes + " trainFile=" + f.getPath());
        try (RelationFileIterator rels = new RelationFileIterator(f, false);
            ManyDocRelationFileIterator many = new ManyDocRelationFileIterator(rels, true)) {
          Iterator<List<RelDoc>> segItr = Iterators.partition(many, trainSegSize);
          int s = 0;
          while (segItr.hasNext()) {
            // Train on segment
            List<RelDoc> segment = segItr.next();
            pipe.runInference(segment.iterator(), "train-epoch" + i + "-segment" + s);

            // Evaluate on mini-dev
            try (RelationFileIterator dr = new RelationFileIterator(dev, false);
                ManyDocRelationFileIterator devDocs = new ManyDocRelationFileIterator(dr, true)) {
              Iterator<RelDoc> miniDev = Iterators.limit(devDocs, miniDevSize);
              pipe.runInference(miniDev, "dev-mini-epoch" + i + "-segment" + s);
            }

            s++;
          }
        }
        // Full evaluate on dev
        pipe.useAvgWeights(true);
        Log.info("[main] pass=" + (i+1) + " of=" + passes + " devFile=" + dev.getPath());
        try (RelationFileIterator rels = new RelationFileIterator(dev, false);
            ManyDocRelationFileIterator many = new ManyDocRelationFileIterator(rels, true)) {
          pipe.runInference(many, "dev-full-epoch" + i);
        }
        // Full evaluate on test
        if (performTest) {
          Log.info("[main] pass=" + (i+1) + " of=" + passes + " testFile=" + test.getPath());
          try (RelationFileIterator rels = new RelationFileIterator(test, false);
              ManyDocRelationFileIterator many = new ManyDocRelationFileIterator(rels, true)) {
            pipe.runInference(many, "test-full-epoch" + i);
          }
        }
        pipe.useAvgWeights(false);
      }
    }
    Log.info("[main] done at " + new java.util.Date().toString());
  }

  public void useAvgWeights(boolean useAvg) {
    for (OldFeaturesWrapper.Ints3 w : feFast3) {
      w.useAverageWeights(useAvg);
    }
    for (GlobalFactor gf : globalFactors) {
      if (gf instanceof NumArgsRoleCoocArgLoc) {
        ((NumArgsRoleCoocArgLoc) gf).useAverageWeights(useAvg);
      }
    }
    assert ofw == null;
    assert feFast2 == null;
    assert feFast == null;
  }

  public void completedObservation() {
    if (feFast3 == null) {
      Log.warn("no feFast3 features!");
    } else {
      for (OldFeaturesWrapper.Ints3 w : feFast3)
        w.completedObservation();
    }
    for (GlobalFactor gf : globalFactors) {
      if (gf instanceof NumArgsRoleCoocArgLoc) {
        ((NumArgsRoleCoocArgLoc) gf).completedObservation();
      }
    }
    assert ofw == null;
    assert feFast2 == null;
    assert feFast == null;
  }

  public UbertsLearnPipeline(Uberts u, File grammarFile, Iterable<File> schemaFiles, File relationDefs) throws IOException {
    super(u, grammarFile, schemaFiles, relationDefs);

    if (predicate2Mutex) {
      NumArgsRoleCoocArgLoc p = new NumArgsRoleCoocArgLoc(u.getEdgeType("predicate2"), 0, 1, u);
      p.storeExactFeatureIndices();
      globalFactors.add(p);
      u.addGlobalFactor(p.getTrigger2(), p);
    }

    ExperimentProperties config = ExperimentProperties.getInstance();
    updateAccordingToPriority = config.getBoolean("dagger/updateAccordingToPriority", false);
    pOracleRollIn = config.getDouble("dagger/pOracleRollIn", pOracleRollIn);
    batchSize = config.getInt("batchSize", batchSize);
    Log.info("[main] updateAccordingToPriority=" + updateAccordingToPriority);
    Log.info("[main] pOracleRollIn=" + pOracleRollIn);
    Log.info("[main] batchSize=" + batchSize);

    if (argument4ByArg && enableGlobalFactors) {
      // argument4(t,f,s,k) with mutexArg=s
      NumArgsRoleCoocArgLoc a = new NumArgsRoleCoocArgLoc(u.getEdgeType("argument4"), 2, 1, u);
      a.storeExactFeatureIndices();
      globalFactors.add(a);
      u.addGlobalFactor(a.getTrigger2(), a);
    }

    if (srl2ByArg && enableGlobalFactors && !skipSrlFilterStages) {
      // srl2(t,s) with mutexArg=s
      NumArgsRoleCoocArgLoc a = new NumArgsRoleCoocArgLoc(u.getEdgeType("srl2"), 1, -1, u);
      a.storeExactFeatureIndices();
      globalFactors.add(a);
      u.addGlobalFactor(a.getTrigger2(), a);
    }

    if (enableGlobalFactors) {
      numArgsArg4 = new NumArgsRoleCoocArgLoc(u.getEdgeType("argument4"), 0, 1, u);
      numArgsArg4.storeExactFeatureIndices();
      globalFactors.add(numArgsArg4);
      u.addGlobalFactor(numArgsArg4.getTrigger2(), numArgsArg4);
    }

    if (enableGlobalFactors && !skipSrlFilterStages) {
      numArgsArg3 = new NumArgsRoleCoocArgLoc(u.getEdgeType("srl3"), 0, -1, u);
      numArgsArg3.storeExactFeatureIndices();
      globalFactors.add(numArgsArg3);
      u.addGlobalFactor(numArgsArg3.getTrigger2(), numArgsArg3);
    }

    if (enableGlobalFactors && !skipSrlFilterStages) {
      numArgsArg2 = new NumArgsRoleCoocArgLoc(u.getEdgeType("srl2"), 0, -1, u);
      numArgsArg2.storeExactFeatureIndices();
      globalFactors.add(numArgsArg2);
      u.addGlobalFactor(numArgsArg2.getTrigger2(), numArgsArg2);
    }
  }

  @Override
  public LocalFactor getScoreFor(Rule r) {
    if (skipSrlFilterStages && Arrays.asList("srl2", "srl3").contains(r.rhs.relName)) {
      return new LocalFactor.Constant(0.5);
    }

    LocalFactor f = LocalFactor.Constant.ZERO;

    if (Arrays.asList(oracleFeats).contains(r.rhs.relName)) {
      Log.info("using oracle feats for " + r);
//      f = new LocalFactor.Sum(new FeatureExtractionFactor.Oracle(r.rhs.relName), f);
      return new FeatureExtractionFactor.Oracle(r.rhs.relName);
    }

    if (templateFeats) {
      ExperimentProperties config = ExperimentProperties.getInstance();
      Log.info("using template feats");
      if (bft == null) {
        bft = new BasicFeatureTemplates();
//        File featureSet = config.getExistingFile("featureSet");
//        ofw = new OldFeaturesWrapper(bft, featureSet);
      }
//      if (feFast2 == null) {
//        int dimension = config.getInt("hashFeatDim", 1 << 18);
//        feFast2 = new OldFeaturesWrapper.Ints2(ofw, dimension);
//      }
//      f = new LocalFactor.Sum(feFast2, f);
      if (feFast3 == null)
        feFast3 = new ArrayList<>();
      OldFeaturesWrapper.Ints3 fe3 = OldFeaturesWrapper.Ints3.build(bft, r.rhs.rel, config);
      feFast3.add(fe3);
      f = new LocalFactor.Sum(fe3, f);
    }

    if (graphFeats) {
      Log.info("using graph feats");
      FeatureExtractionFactor.GraphWalks gw = new FeatureExtractionFactor.GraphWalks();
      gw.maxArgs = 6;
      //    gw.maxValues = gw.maxArgs;
      f = new LocalFactor.Sum(gw, f);
    }

    if (DEBUG > 0)
      Log.info("scoreFor(" + r + "): " + f);
    assert f != LocalFactor.Constant.ZERO;
    return f;
  }

  @Override
  public void start(String dataName) {
    super.start(dataName);
    if (dataName.contains("test"))
      mode = Mode.TEST;
    else if (dataName.contains("dev"))
      mode = Mode.DEV;
    else if (dataName.contains("train"))
      mode = Mode.TRAIN;
    else
      throw new RuntimeException("don't know how to handle " + dataName);
  }

  @Override
  public void finish(String dataName) {
    super.finish(dataName);
    eventCounts.increment("pass/" + mode.toString());
    Log.info("mode=" + mode + " dataName=" + dataName + "\t" + eventCounts.toString());

    // Compute performance over last segment
    Map<String, FPR> perf = new HashMap<>();
    for (Map<String, FPR> p : perfByRel)
      perf = Labels.combinePerfByRel(perf, p);
    perfByRel.clear();
    for (String line : Labels.showPerfByRel(perf))
      System.out.println("[main] " + dataName + ": " + line);

    // Timing information
    timer.printAll(System.out);

    // Memory usage
    Log.info(Describe.memoryUsage());

    if (DEBUG > 1) {
      if (numArgsArg4 != null)
        Log.info("numArgsArg4 biggest weights: " + numArgsArg4.getBiggestWeights(10));
      if (numArgsArg3 != null)
        Log.info("numArgsArg3 biggest weights: " + numArgsArg3.getBiggestWeights(10));
      if (numArgsArg2 != null)
        Log.info("numArgsArg2 biggest weights: " + numArgsArg2.getBiggestWeights(10));
    }

    mode = null;
  }

  @Override
  public void consume(RelDoc doc) {
    if (MEM_LEAK_DEBUG)
      return;
//    System.out.println("[consume] " + doc.getId());

    eventCounts.increment("consume/" + mode.toString());
    if (eventCounts.getTotalCount() % 500 == 0) {
      Log.info("event counts: " + eventCounts);
      System.out.println("[memLeak] " + u.getState());
    }
    u.stats.clear();
    switch (mode) {
    case TRAIN:
      train(doc);
      break;
    case DEV:
    case TEST:
      if (DEBUG > 0 && perfByRel.size() % 25 == 0) {
        System.out.println("mode=" + mode + " doc=" + doc.getId() + " [memLeak] perfByRel.size=" + perfByRel.size());
      }
      boolean oracle = false;
      int actionLimit = 0;

      timer.start("inf/" + mode);
      Pair<Perf, List<Step>> p = u.dbgRunInference(oracle, actionLimit);
      timer.stop("inf/" + mode);
      perfByRel.add(p.get1().perfByRel());

      if (DEBUG > 0) {// && perfByRel.size() % 50 == 0) {
        if (DEBUG > 1) {
          // Steps
          for (Step s : p.get2())
            System.out.println("step: " + s);

          // False negatives
          List<HypEdge> srl3FNs = p.get1().getFalseNegatives(u.getEdgeType("srl3"));
          for (HypEdge e : srl3FNs)
            System.out.println("prediction FN: " + e);
        }

        // Show some stats about this example
        System.out.println("trajLength=" + p.get2().size());
        System.out.println("perDocStats: " + u.stats.toString());

        // Agenda size
        OrderStatistics<Integer> os = new OrderStatistics<>();
        assert u.statsAgendaSizePerStep.size() > 0 || p.get2().size() == 0;
        for (int i = 0; i < u.statsAgendaSizePerStep.size(); i++)
          os.add(u.statsAgendaSizePerStep.get(i));
        System.out.println("agendaSize: mean=" + os.getMean() + " orders=" + os.getOrdersStr());

        // Sentence length
        LL<HypEdge> words = u.getState().match2(u.getEdgeType("word2"));
        int nWords = 0;
        for (LL<HypEdge> cur = words; cur != null; cur = cur.next) nWords++;
        System.out.println("nWords=" + nWords);

        StringBuilder sb = new StringBuilder();
        sb.append("globalFactorStats:");
        for (GlobalFactor gf : globalFactors) {
          String msg = gf.getStats();
          if (msg != null) {
            sb.append(' ');
            sb.append('(');
            sb.append(gf.getName());
            sb.append(' ');
            sb.append(msg);
            sb.append(')');
          }
        }
        System.out.println(sb.toString());

        if (DEBUG > 1) {
          System.out.println(StringUtils.join("\n", Labels.showPerfByRel(perfByRel.get(perfByRel.size()-1))));
        }
      }
      break;
    }

    u.clearAgenda();
    u.getState().clearNonSchema();
  }

  public Map<Relation, Double> getCostFP() {
    Map<Relation, Double> costFP = new HashMap<>();
    h("argument4", 1d, costFP, u);
    h("srl3", costFP_srl3, costFP, u);
    h("srl2", costFP_srl2, costFP, u);
    h("predicate2", 1d, costFP, u);
    h("event1", costFP_event1, costFP, u);
    return costFP;
  }
  private static void h(String name, double costFP, Map<Relation, Double> maybeAddTo, Uberts u) {
    Relation r = u.getEdgeType(name, true);
    if (r == null) {
      if (DEBUG > 1)
        Log.info("not adding costFP for " + name + " since it doesn't exist in uberts!");
    } else {
      Double old = maybeAddTo.put(r, costFP);
      assert old == null;
    }
  }

  private void train(RelDoc doc) {
    if (DEBUG > 1)
      Log.info("starting on " + doc.getId());

    switch (trainMethod) {
    case EARLY_UPDATE:
      timer.start("train/earlyUpdate");
      Step mistake = u.earlyUpdatePerceptron();
      batch.add(lr -> {
        if (mistake != null) {
          if (mistake.gold) {
            assert !mistake.pred;
            mistake.score.backwards(lr * -1);
          } else {
            assert mistake.pred;
            mistake.score.backwards(lr * +1);
          }
        }
      });
      timer.stop("train/earlyUpdate");
      break;
    case MAX_VIOLATION:
      timer.start("train/maxViolation");
      Pair<Traj, Traj> maxViolation = u.maxViolationPerceptron(getCostFP());
      batch.add(lr -> {
        if (maxViolation != null) {
          for (Traj cur = maxViolation.get1(); cur != null; cur = cur.getPrev()) {
            Step s = cur.getStep();
            if (s.gold)
              s.score.backwards(lr * -1);
          }
          for (Traj cur = maxViolation.get2(); cur != null; cur = cur.getPrev()) {
            Step s = cur.getStep();
            if (!s.gold)
              s.score.backwards(lr * +1);
          }
        }
      });
      timer.stop("train/maxViolation");
      break;
    case DAGGER:
      timer.start("train/dagger");
      List<AgendaSnapshot> states = u.daggerLike(pOracleRollIn);
      Map<Relation, Double> costFP = getCostFP();
      batch.add(lr -> {
        int i = 0;
        for (AgendaSnapshot s : states) {
          if (Uberts.DEBUG > 2)
            System.out.println("update from state (agenda snapshot) " + (++i) + " of " + states.size());
          s.applyUpdate(lr, updateAccordingToPriority, costFP);
        }
      });
      timer.stop("train/dagger");
      break;
    case DAGGER1:
      timer.start("train/dagger1");
      boolean verbose = false;
      int actionLimit = 0;
      boolean oracle = u.getRandom().nextDouble() < pOracleRollIn;
      Pair<Perf, List<Step>> x = u.dbgRunInference(oracle, actionLimit);
      batch.add(lr -> {
        Map<Relation, Double> cfp = getCostFP();
        if (verbose) {
          System.out.println("about to update against length=" + x.get2().size()
                + " trajectory, costFP=" + cfp + " oracleRollIn=" + oracle);
        }
        for (Step s : x.get2()) {

          // NOTE: We are NOT using minScorePerRelation here since we only want
          // to move scores about 0, not the threshold.
          boolean pred = s.score.forwards() > 0;
//          boolean pred = s.pred;

          if (s.gold && !pred) {
            if (verbose) System.out.println("FN: " + s);
            s.score.backwards(-lr);
          } else if (!s.gold && pred) {
            if (verbose) System.out.println("FP: " + s);
            s.score.backwards(+lr * cfp.getOrDefault(s.edge.getRelation(), 1d));
          } else if (verbose) {
            if (s.gold)
              System.out.println("TP: " + s);
            else
              System.out.println("TN: " + s);
          }
        }
      });
      timer.stop("train/dagger1");
      break;
    default:
      throw new RuntimeException("implement " + trainMethod);
    }

    if (batch.size() == batchSize) {
      timer.start("train/batchApply");
      for (Consumer<Double> u : batch)
        u.accept(1d / batchSize);
      batch.clear();
      completedObservation();
      timer.stop("train/batchApply");
    }
  }

}
