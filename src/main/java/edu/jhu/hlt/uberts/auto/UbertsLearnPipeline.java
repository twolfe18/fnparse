package edu.jhu.hlt.uberts.auto;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.google.common.collect.Iterators;

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
import edu.jhu.hlt.tutils.OrderStatistics;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.AgendaPriority;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Labels;
import edu.jhu.hlt.uberts.Labels.Perf;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Relation.EqualityArray;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Step;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.Uberts.AgendaSnapshot;
import edu.jhu.hlt.uberts.Uberts.Traj;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorBackwardsParser.Iter;
import edu.jhu.hlt.uberts.factor.AtMost1;
import edu.jhu.hlt.uberts.factor.GlobalFactor;
import edu.jhu.hlt.uberts.factor.LocalFactor;
import edu.jhu.hlt.uberts.factor.NumArgsRoleCoocArgLoc;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.hlt.uberts.features.OldFeaturesWrapper;
import edu.jhu.hlt.uberts.features.WeightAdjoints;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.prim.tuple.Pair;

public class UbertsLearnPipeline extends UbertsPipeline {
  public static int DEBUG = 1;  // 0 means off, 1 means coarse, 2+ means fine grain logging

  static enum TrainMethod {
    EARLY_UPDATE,
    MAX_VIOLATION,
    DAGGER,
  }

  static boolean pizza = false;
  static boolean oracleFeats = true;
  static boolean graphFeats = false;
  static boolean templateFeats = false;
  static boolean pipeline = false;
//  static boolean maxViolation = true;
  static TrainMethod trainMethod = TrainMethod.DAGGER;

  static boolean predicate2Mutex = true;
  static boolean enableGlobalFactors = true;

  static double costFP_srl3 = 0.1;
  static double costFP_srl2 = 0.25;
  static double costFP_event1 = 0.25;

  // maxViolation=true  n=150 pipeline=false  got close to F1=1
  // maxViolation=true  n=5   pipeline=true   cFP=1   F(predicate2)=29.0
  // maxViolation=true  n=5   pipeline=false  cFP=1   F(predicate2)=86.5 F(argument4)=14.0
  // maxViolation=true  n=5   pipeline=true   cFP=1/4 F(predicate2)=46.8  says the same when I go to 0.125 or 0.0125
  // maxViolation=true  n=5   pipeline=false  cFP=1   F(predicate2)=86.5 F(argument4)=14.0
  // +numArgs F(predicate2)=90.7 F(argument4)=33.0
  // Surprising that +numArgs improves F(predicate2), presumably because you get more training examples not wasted
  // +pred2Mutex +numArgs F(predicate2)=100 F(argument4)=13.3 ...interesting
  // +pred2Mutex(SOFT) +numArgs F(predicate2)=88.7 F(argument4)=36.7

  public static void main(String[] args) throws IOException {
    Log.info("[main] starting at " + new java.util.Date().toString());
    ExperimentProperties config = ExperimentProperties.init(args);
//    BrownClusters.DEBUG = true; // who is loading BC multiple times?

//    File p;
    File grammarFile = config.getExistingFile("grammar");
    File relationDefs = config.getExistingFile("relations");
    List<File> schemaFiles = config.getExistingFiles("schema");
//    if (pizza) {
//      p = new File("data/srl-reldata/trump-pizza");
//      grammarFile = new File(p, "grammar.trans");
//      schemaFiles = Arrays.asList(
//          new File(p, "span-relations.def"),
//          new File(p, "schema-framenet.def"));
//      relationDefs = new File(p, "relations.def");
////    File multiRels = new File(p, "trump-pizza-fork.backwards.xy.rel.multi");
//    } else {
//      p = new File("data/srl-reldata/propbank");
//      grammarFile = new File("data/srl-reldata/grammar/srl-grammar-current.trans");
//      schemaFiles = Arrays.asList(
//          new File("data/srl-reldata/util/span-250.def"),
//          new File(p, "frameTriage.rel.gz"),
//          new File(p, "role.rel.gz"));
//      relationDefs = new File(p, "relations.def");
//    }

    costFP_event1 = config.getDouble("costFP_event1", costFP_event1);
    costFP_srl2 = config.getDouble("costFP_srl2", costFP_srl2);
    costFP_srl3 = config.getDouble("costFP_srl3", costFP_srl3);
    Log.info("[main]"
        + " costFP_event1=" + costFP_event1
        + " costFP_srl2=" + costFP_srl2
        + " costFP_srl3=" + costFP_srl3);

    oracleFeats = config.getBoolean("oracleFeats", false);
    graphFeats = config.getBoolean("graphFeats", false);
    templateFeats = config.getBoolean("templateFeats", true);

    trainMethod = TrainMethod.valueOf(config.getString("trainMethod", TrainMethod.DAGGER.toString()));
    Log.info("[main] trainMethod=" + trainMethod);

//    NumArgsRoleCoocArgLoc.IMMUTABLE_FACTORS = true;
//    NumArgsRoleCoocArgLoc.DEBUG = 2;

    predicate2Mutex = config.getBoolean("pred2Mutex", true);
    Log.info("predicate2Mutex=" + predicate2Mutex);

    String gfm = config.getString("globalFeatMode");
    Log.info("[main] globalFeatMode=" + gfm);
    switch (gfm) {
    case "none":
      enableGlobalFactors = false;
      NumArgsRoleCoocArgLoc.numArgs = false;
      NumArgsRoleCoocArgLoc.argLocPairwise = false;
      NumArgsRoleCoocArgLoc.argLocGlobal = false;
      NumArgsRoleCoocArgLoc.roleCooc = false;
      break;
    case "argLoc":
      enableGlobalFactors = true;
      NumArgsRoleCoocArgLoc.numArgs = false;
      NumArgsRoleCoocArgLoc.argLocPairwise = true;
      NumArgsRoleCoocArgLoc.argLocGlobal = true;
      NumArgsRoleCoocArgLoc.roleCooc = false;
      break;
    case "numArgs":
      enableGlobalFactors = true;
      NumArgsRoleCoocArgLoc.numArgs = true;
      NumArgsRoleCoocArgLoc.argLocPairwise = false;
      NumArgsRoleCoocArgLoc.argLocGlobal = false;
      NumArgsRoleCoocArgLoc.roleCooc = false;
      break;
    case "roleCooc":
      enableGlobalFactors = true;
      NumArgsRoleCoocArgLoc.numArgs = false;
      NumArgsRoleCoocArgLoc.argLocPairwise = false;
      NumArgsRoleCoocArgLoc.argLocGlobal = false;
      NumArgsRoleCoocArgLoc.roleCooc = true;
      break;
    case "full":
      enableGlobalFactors = true;
      NumArgsRoleCoocArgLoc.numArgs = true;
      NumArgsRoleCoocArgLoc.argLocPairwise = true;
      NumArgsRoleCoocArgLoc.argLocGlobal = true;
      NumArgsRoleCoocArgLoc.roleCooc = true;
      break;
    default:
      throw new RuntimeException("unknown globalFeatMode: " + gfm);
    }

    NumArgsRoleCoocArgLoc.numArgs = config.getBoolean("numArgs", true);
    NumArgsRoleCoocArgLoc.argLocPairwise = config.getBoolean("argLocPairwise", true);
    NumArgsRoleCoocArgLoc.argLocGlobal = config.getBoolean("argLocGlobal", true);
    NumArgsRoleCoocArgLoc.roleCooc = config.getBoolean("roleCooc", true);

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
     * for each pass over all train data:
     *    for each segment of size N:
     *       train(train-segment)
     *       eval(dev-small)  # can be the first N documents in the dev set
     *    eval(dev)
     *    eval(test)
     */

    // Train and dev should be shuffled. Test doesn't need to be.
    List<File> train = config.getExistingFiles("train.facts");
    File dev = config.getExistingFile("dev.facts");
    File test = config.getExistingFile("test.facts");

    // The ratio of miniDevSize/trainSegSize is the price of knowing how well
    // you're doing during training. Try to minimize it to keep train times down.
    int miniDevSize = config.getInt("miniDevSize", 300);
    int trainSegSize = config.getInt("trainSegSize", miniDevSize * 20);

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
        Log.info("[main] pass=" + (i+1) + " of=" + passes + " devFile=" + dev.getPath());
        try (RelationFileIterator rels = new RelationFileIterator(dev, false);
            ManyDocRelationFileIterator many = new ManyDocRelationFileIterator(rels, true)) {
          pipe.runInference(many, "dev-full-epoch" + i);
        }
        // Full evaluate on test
        Log.info("[main] pass=" + (i+1) + " of=" + passes + " testFile=" + test.getPath());
        try (RelationFileIterator rels = new RelationFileIterator(test, false);
            ManyDocRelationFileIterator many = new ManyDocRelationFileIterator(rels, true)) {
          pipe.runInference(many, "test-full-epoch" + i);
        }
      }
    }
    Log.info("[main] done at " + new java.util.Date().toString());
  }

  // How should the document being consumed be interpretted?
  public static enum Mode {
    TRAIN, DEV, TEST,
  }
  private Mode mode;

  private BasicFeatureTemplates bft;
  private OldFeaturesWrapper ofw;
  private OldFeaturesWrapper.Ints2 feFast2;
  private List<OldFeaturesWrapper.Ints3> feFast3;
  /** @deprecated */
  private OldFeaturesWrapper.Ints feFast;

  private List<Consumer<Double>> batch = new ArrayList<>();
  private int batchSize = 32;
  private boolean updateAccordingToPriority = false;
  private double pOracleRollIn = 1.0;

  private NumArgsRoleCoocArgLoc numArgsArg4;
  private NumArgsRoleCoocArgLoc numArgsArg3;
  private NumArgsRoleCoocArgLoc numArgsArg2;
  // These are not mutually exclusive, above are in below
  private List<GlobalFactor> globalFactors = new ArrayList<>();

  // For now these store all performances for the last data segment
  private List<Map<String, FPR>> perfByRel = new ArrayList<>();

  // Keeps track of how frequently types of events happen
  private Counts<String> eventCounts = new Counts<>();

  // How long a train/update takes
  private Timer trainTimer = new Timer("train", 1000, true);

  public UbertsLearnPipeline(Uberts u, File grammarFile, Iterable<File> schemaFiles, File relationDefs) throws IOException {
    super(u, grammarFile, schemaFiles, relationDefs);

    if (predicate2Mutex) {
      boolean hard = false;
      AtMost1.add(u, u.getEdgeType("predicate2"), 0 /* t in predicate2(t,f) */, hard);
    }

    ExperimentProperties config = ExperimentProperties.getInstance();
    updateAccordingToPriority = config.getBoolean("dagger/updateAccordingToPriority", false);
    pOracleRollIn = config.getDouble("dagger/pOracleRollIn", 1.0);
    batchSize = config.getInt("batchSize", 32);
    Log.info("[main] updateAccordingToPriority=" + updateAccordingToPriority);
    Log.info("[main] pOracleRollIn=" + pOracleRollIn);
    Log.info("[main] batchSize=" + batchSize);

    // TODO argument4(t,f,s,k) with mutexArg=s?
    // TODO srl2(t,s) with mutexArg=s?

    numArgsArg4 = new NumArgsRoleCoocArgLoc(u.getEdgeType("argument4"), 0, 1, u);
    numArgsArg4.storeExactFeatureIndices();
    if (enableGlobalFactors) {
      globalFactors.add(numArgsArg4);
      u.addGlobalFactor(numArgsArg4.getTrigger(u), numArgsArg4);
    }

    numArgsArg3 = new NumArgsRoleCoocArgLoc(u.getEdgeType("srl3"), 0, -1, u);
    numArgsArg3.storeExactFeatureIndices();
    if (enableGlobalFactors) {
      globalFactors.add(numArgsArg3);
      u.addGlobalFactor(numArgsArg3.getTrigger(u), numArgsArg3);
    }

    numArgsArg2 = new NumArgsRoleCoocArgLoc(u.getEdgeType("srl2"), 0, -1, u);
    numArgsArg2.storeExactFeatureIndices();
    if (enableGlobalFactors) {
      globalFactors.add(numArgsArg2);
      u.addGlobalFactor(numArgsArg2.getTrigger(u), numArgsArg2);
    }
  }

  @Override
  public LocalFactor getScoreFor(Rule r) {
    LocalFactor f = new LocalFactor.Zero();

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

    if (oracleFeats) {
      Log.info("using oracle feats");
      f = new LocalFactor.Sum(new FeatureExtractionFactor.Oracle(r.rhs.relName), f);
    }

    if (graphFeats) {
      Log.info("using graph feats");
      FeatureExtractionFactor.GraphWalks gw = new FeatureExtractionFactor.GraphWalks();
      gw.maxArgs = 6;
      //    gw.maxValues = gw.maxArgs;
      f = new LocalFactor.Sum(gw, f);
    }

    assert !(f instanceof LocalFactor.Zero);
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
      System.out.println(dataName + ": " + line);

    // Timing information
    timer.printAll(System.out);

    // Memory usage
    Log.info(Describe.memoryUsage());

    if (DEBUG > 1) {
      Log.info("numArgsArg4 biggest weights: " + numArgsArg4.getBiggestWeights(10));
      Log.info("numArgsArg3 biggest weights: " + numArgsArg3.getBiggestWeights(10));
      Log.info("numArgsArg2 biggest weights: " + numArgsArg2.getBiggestWeights(10));
    }

    mode = null;
  }

  @Override
  public void consume(RelDoc doc) {
    eventCounts.increment("consume/" + mode.toString());
    if (eventCounts.getTotalCount() % 500 == 0)
      Log.info("event counts: " + eventCounts);
    u.stats.clear();
    switch (mode) {
    case TRAIN:
      train(doc);
      break;
    case DEV:
    case TEST:
      if (DEBUG > 0)
        Log.info("mode=" + mode + " doc=" + doc.getId());
      boolean oracle = false;
      double minScore = 0.0001;
      int actionLimit = 0;

      timer.start("inf/" + mode);
      Pair<Perf, List<Step>> p = u.dbgRunInference(oracle, minScore, actionLimit);
      timer.stop("inf/" + mode);
      perfByRel.add(p.get1().perfByRel());

      if (DEBUG > 0 && perfByRel.size() % 5 == 0) {
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
  }

  public Map<Relation, Double> getCostFP() {
    Map<Relation, Double> costFP = new HashMap<>();
    costFP.put(u.getEdgeType("argument4"), 1d);
    costFP.put(u.getEdgeType("srl3"), costFP_srl3);
    costFP.put(u.getEdgeType("srl2"), costFP_srl2);
    costFP.put(u.getEdgeType("predicate2"), 1d);
    costFP.put(u.getEdgeType("event1"), costFP_event1);
    return costFP;
  }

  private void train(RelDoc doc) {
    trainTimer.start();
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
        for (AgendaSnapshot s : states)
          s.applyUpdate(lr, updateAccordingToPriority, costFP);
      });
      timer.stop("train/dagger");
      break;
    default:
      throw new RuntimeException("implement " + trainMethod);
    }

    if (batch.size() == batchSize) {
      timer.start("train/batchApply");
      for (Consumer<Double> u : batch)
        u.accept(1d / batchSize);
      batch.clear();
      timer.stop("train/batchApply");
    }

    trainTimer.stop();
  }


  /**
   * @deprecated
   */
  private void init() {
    if (bft != null)
      return;
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
    assert ts.length() > 0 : "no features";
    //      String ts = prependRefinementTemplate("arg", negFsFile)
    //          + " + " + prependRefinementTemplate("argAndRoleArg", posFsFile);
    //      File bialph = new File("data/mimic-coe/framenet/coherent-shards/alphabet.txt.gz");
    //      File fcounts = new File("data/mimic-coe/framenet/feature-counts/all.txt.gz");
    File bialph = config.getExistingFile("bialph");
    File fcounts = config.getExistingFile("featureCounts");
    try {
      feFast = new OldFeaturesWrapper.Ints(new OldFeaturesWrapper(bft, ts, bialph, fcounts), numBits);
      feFast.cacheAdjointsForwards = true;
      assert feFast.getInner() != null;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * @deprecated
   */
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
  /** @deprecated */
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
            //              HypEdge goldKE = null;
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
                //                  goldKE = srl4E;
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
   *
   * @deprecated
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