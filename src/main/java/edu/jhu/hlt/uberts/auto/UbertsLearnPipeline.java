package edu.jhu.hlt.uberts.auto;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.common.collect.Iterators;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.Pred2ArgPaths;
import edu.jhu.hlt.fnparse.inference.heads.DependencyHeadFinder;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.pruning.FNParseSpanPruning;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.OrderStatistics;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.AgendaPriority;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
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
import edu.jhu.hlt.uberts.factor.NumArgsRoleCoocArgLoc.Params;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.hlt.uberts.features.OldFeaturesWrapper;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.PerlRegexFileInputStream;
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

  // Useful when you use oracle features and test out various hard constraints
  // which may not allow you to get 100% recall.
  static boolean showDevFN = false;

  static String[] oracleFeats = new String[] {};
  static boolean graphFeats = false;
  static boolean templateFeats = true;
  static boolean pipeline = false;
  public static TrainMethod trainMethod = TrainMethod.DAGGER1;

  // Global features which aggregate based on s instead of t in argument2(t,f,s,k)
  static boolean srl2ByArg = true;
  static boolean argument4ByArg = true;

  static NumArgsRoleCoocArgLoc.Params allowableGlobals;

  // small costFP for srl2/srl3 so far have no paid off, HOWEVER, I think this
  // may have been due to having a small intercept feature.
  static double costFP_srl3 = 0.1;
  static double costFP_srl2 = 0.1;
  static double costFP_event1 = 1;

  private Mode mode;

  private BasicFeatureTemplates bft;
  private List<OldFeaturesWrapper.Ints3> feFast3;

  private ParameterIO parameterIO;

  private List<Consumer<Double>> batch = new ArrayList<>();
  private int batchSize = 1;
  private boolean updateAccordingToPriority = false;
  private double pOracleRollIn = 1;

  private static boolean skipSrlFilterStages = false;

  private NumArgsRoleCoocArgLoc numArgsArg4;
  private NumArgsRoleCoocArgLoc numArgsArg3;
  private NumArgsRoleCoocArgLoc numArgsArg2;
  // These fields are not mutually exclusive, above are in below
  private List<GlobalFactor> globalFactors = new ArrayList<>();

  // For now these store all performances for the last data segment
  private List<Map<String, FPR>> perfByRel = new ArrayList<>();

  // For writing out predictions for dev/test data
  private File predictionsDir;
  private BufferedWriter predictionsWriter;
  private boolean includeNegativePredictions = false;

  // See DAGGER1
  private boolean newMarginUpdate = true;


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

    showDevFN = config.getBoolean("showDevFN", showDevFN);

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
    graphFeats = config.getBoolean("graphFeats", graphFeats);
    templateFeats = config.getBoolean("templateFeats", templateFeats);

    trainMethod = TrainMethod.valueOf(config.getString("trainMethod", trainMethod.toString()));
    Log.info("[main] trainMethod=" + trainMethod);

    allowableGlobals = new Params();
    allowableGlobals.frameCooc = config.getBoolean("frameCooc");

    String gfm = config.getString("globalFeatMode");
    Log.info("[main] globalFeatMode=" + gfm);
    switch (gfm.toLowerCase()) {
    case "debug":
      allowableGlobals.frameCooc = false;
      allowableGlobals.numArgs = true;
      allowableGlobals.argLocPairwise = true;
      allowableGlobals.argLocGlobal = true;
      allowableGlobals.roleCooc = false;
      allowableGlobals.argLocRoleCooc = true;
      break;
    case "none":
    case "off":
      break;
    case "argloc":
      allowableGlobals.argLocPairwise = true;
      allowableGlobals.argLocGlobal = true;
      break;
    case "arglocpairwise":
      allowableGlobals.argLocPairwise = true;
      break;
    case "numarg":
    case "numargs":
      allowableGlobals.numArgs = true;
      break;
    case "rolecooc":
      allowableGlobals.roleCooc = true;
      break;
    case "full":
    case "all":
      allowableGlobals.frameCooc = true;
      allowableGlobals.numArgs = true;
      allowableGlobals.argLocPairwise = true;
      allowableGlobals.argLocGlobal = true;
      allowableGlobals.roleCooc = true;
      allowableGlobals.argLocRoleCooc = true;
      break;
    default:
      throw new RuntimeException("unknown globalFeatMode: " + gfm);
    }

    Log.info("[main] allowableGlobals=" + allowableGlobals);

    // This is how many passes over ALL training files are given. If there are
    // 10 train files, each of which is all the data shuffled in a particular
    // way, then setting this to 3 would mean making 30 epochs.
    int passes = config.getInt("passes", 10);
    Log.info("[main] passes=" + passes);

    String ap = config.getString("agendaPriority");
    BiFunction<HypEdge, Adjoints, Double> agendaPriority =
        AgendaPriority.parse(ap);
    Log.info("[main] agendaPriority=" + ap.replaceAll("\\s+", "_"));

    Uberts u = new Uberts(new Random(9001), agendaPriority);
    UbertsLearnPipeline pipe = new UbertsLearnPipeline(u, grammarFile, schemaFiles, relationDefs);

    pipe.newMarginUpdate = config.getBoolean("newMarginUpdate", pipe.newMarginUpdate);
    Log.info("[main] newMarginUpdate=" + pipe.newMarginUpdate);

    pipe.predictionsDir = config.getFile("predictions.outputDir", null);
    if (pipe.predictionsDir != null) {
      pipe.includeNegativePredictions = config.getBoolean(
          "predictions.includeNegativePredictions", pipe.includeNegativePredictions);
      Log.info("[main] writing predictions to " + pipe.predictionsDir.getPath()
          + " includeNegativePredictions=" + pipe.includeNegativePredictions);
    }

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

    // Option: run a perl regex over the train/dev/test files
    String dataRegex = config.getString("dataRegex", "");
    List<Supplier<InputStream>> train2 = new ArrayList<>();
    Supplier<InputStream> dev2;
    Supplier<InputStream> test2;
    if (!dataRegex.isEmpty()) {
      Log.info("[main] applying dataRegex=" + dataRegex);
      for (File f : train)
        train2.add(() -> new PerlRegexFileInputStream(f, dataRegex).startOrBlowup());
      dev2 = () -> new PerlRegexFileInputStream(dev, dataRegex).startOrBlowup();
      test2 = () -> new PerlRegexFileInputStream(test, dataRegex).startOrBlowup();
    } else {
      for (File f : train)
        train2.add(() -> FileUtil.getInputStreamOrBlowup(f));
      dev2 = () -> FileUtil.getInputStreamOrBlowup(dev);
      test2 = () -> FileUtil.getInputStreamOrBlowup(test);
    }

    /*
     * for each pass over all train data:
     *    for each segment of size N:
     *       train(train-segment)
     *       eval(dev-small)  # can be the first N documents in the dev set
     *    eval(dev)
     *    eval(test)
     */
    for (int i = 0; i < passes; i++) {
      for (int trainIdx = 0; trainIdx < train.size(); trainIdx++) {
        Log.info("[main] pass=" + (i+1) + " of=" + passes + " trainFile=" + train.get(trainIdx).getPath());
        try (InputStream is = train2.get(trainIdx).get();
            RelationFileIterator rels = new RelationFileIterator(is);
            ManyDocRelationFileIterator many = new ManyDocRelationFileIterator(rels, true)) {
          Iterator<List<RelDoc>> segItr = Iterators.partition(many, trainSegSize);
          int s = 0;
          while (segItr.hasNext()) {
            // Train on segment
            List<RelDoc> segment = segItr.next();
            pipe.runInference(segment.iterator(), "train-epoch" + i + "-segment" + s);

            // Evaluate on mini-dev
            try (InputStream dev2is = dev2.get();
                RelationFileIterator dr = new RelationFileIterator(dev2is);
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
        try (InputStream dev2is = dev2.get();
            RelationFileIterator rels = new RelationFileIterator(dev2is);
            ManyDocRelationFileIterator many = new ManyDocRelationFileIterator(rels, true)) {
          pipe.runInference(many, "dev-full-epoch" + i);
        }
        // Full evaluate on test
        if (performTest) {
          Log.info("[main] pass=" + (i+1) + " of=" + passes + " testFile=" + test.getPath());
          try (InputStream test2is = test2.get();
              RelationFileIterator rels = new RelationFileIterator(test2is);
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
  }

  public void completedObservation() {
    if (feFast3 == null) {
      if (DEBUG > 1)
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
  }

  public UbertsLearnPipeline(Uberts u, File grammarFile, Iterable<File> schemaFiles, File relationDefs) throws IOException {
    super(u, grammarFile, schemaFiles, relationDefs);

    if (allowableGlobals.frameCooc) {
      Params gp = new Params();
      gp.frameCooc = true;
      NumArgsRoleCoocArgLoc p = new NumArgsRoleCoocArgLoc(u.getEdgeType("predicate2"), 0, 1, gp, u);
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

    if (srl2ByArg && !skipSrlFilterStages) {
      // srl2(t,s) with mutexArg=s
      Relation srl2 = u.getEdgeType("srl2", true);
      if (srl2 == null) {
        Log.warn("there is no srl2 relation, did you want skipSrlFilterStages=true? NOT ADDING GLOBAL FACTOR.");
      } else {
        Params p = new Params();
        p.argLocGlobal = true;
        p.argLocPairwise = true;
        p.numArgs = true;
        p.and(allowableGlobals);
        NumArgsRoleCoocArgLoc a = new NumArgsRoleCoocArgLoc(srl2, 1, -1, p, u);
        a.storeExactFeatureIndices();
        globalFactors.add(a);
        u.addGlobalFactor(a.getTrigger2(), a);
      }
    }

    if (argument4ByArg) {
      // argument4(t,f,s,k) with mutexArg=s
      Params p = new Params();
      p.argLocGlobal = true;
      p.argLocPairwise = true;
      p.argLocRoleCooc = true;
      p.numArgs = true;
      p.roleCooc = true;
      p.and(allowableGlobals);
      NumArgsRoleCoocArgLoc a = new NumArgsRoleCoocArgLoc(u.getEdgeType("argument4"), 2, 1, p, u);
      a.storeExactFeatureIndices();
      globalFactors.add(a);
      u.addGlobalFactor(a.getTrigger2(), a);
    }

    if (!skipSrlFilterStages) {
      Relation srl2 = u.getEdgeType("srl2", true);
      if (srl2 == null) {
        Log.warn("there is no srl2 relation, did you want skipSrlFilterStages=true? NOT ADDING GLOBAL FACTOR.");
      } else {
        Params p = new Params();
        p.argLocGlobal = true;
        p.argLocPairwise = true;
        p.numArgs = true;
        p.and(allowableGlobals);
        numArgsArg2 = new NumArgsRoleCoocArgLoc(srl2, 0, -1, p, u);
        numArgsArg2.storeExactFeatureIndices();
        globalFactors.add(numArgsArg2);
        u.addGlobalFactor(numArgsArg2.getTrigger2(), numArgsArg2);
      }
    }

    if (!skipSrlFilterStages) {
      Relation srl3 = u.getEdgeType("srl3", true);
      if (srl3 == null) {
        Log.warn("there is no srl3 relation, did you want skipSrlFilterStages=true? NOT ADDING GLOBAL FACTOR.");
      } else {
        Params p = new Params();
        p.numArgs = true;
        p.roleCooc = true;
        p.and(allowableGlobals);
        numArgsArg3 = new NumArgsRoleCoocArgLoc(srl3, 0, -1, p, u);
        numArgsArg3.storeExactFeatureIndices();
        globalFactors.add(numArgsArg3);
        u.addGlobalFactor(numArgsArg3.getTrigger2(), numArgsArg3);
      }
    }

    Params p = new Params();
    p.argLocGlobal = true;
    p.argLocPairwise = true;
    p.argLocRoleCooc = true;
    p.numArgs = true;
    p.roleCooc = true;
    p.and(allowableGlobals);
    if (p.any()) {
      numArgsArg4 = new NumArgsRoleCoocArgLoc(u.getEdgeType("argument4"), 0, 1, p, u);
      numArgsArg4.storeExactFeatureIndices();
      globalFactors.add(numArgsArg4);
      u.addGlobalFactor(numArgsArg4.getTrigger2(), numArgsArg4);
    }

    getParameterIO();
  }

  private ParameterIO getParameterIO() {
    if (parameterIO == null) {
      parameterIO = new ParameterIO();
      parameterIO.configure(ExperimentProperties.getInstance());
    }
    return parameterIO;
  }

  @Override
  public LocalFactor getScoreFor(Rule r) {
    if (skipSrlFilterStages && Arrays.asList("srl2", "srl3").contains(r.rhs.relName)) {
      return new LocalFactor.Constant(0.5);
    }

    LocalFactor f = LocalFactor.Constant.ZERO;

    if (Arrays.asList(oracleFeats).contains(r.rhs.relName)) {
      Log.info("using oracle feats for " + r);
//      return new FeatureExtractionFactor.Oracle(r.rhs.relName);
      return new LocalFactor() {
        @Override
        public Adjoints score(HypEdge yhat, Uberts x) {
          boolean y = x.getLabel(yhat);
          // This needs to be >1 so that it over-rides Agenda.RescoreMode.LOSS_AUGMENTED
          if (y)
            return new Adjoints.Constant(+2);
          else
            return new Adjoints.Constant(-2);
        }
      };
    }

    LocalFactor f2 = getParameterIO().get(r);
    if (f2 != null)
      return f2;

    if (templateFeats) {
      ExperimentProperties config = ExperimentProperties.getInstance();
      Log.info("using template feats");
      if (bft == null)
        bft = new BasicFeatureTemplates();
      if (feFast3 == null)
        feFast3 = new ArrayList<>();
      OldFeaturesWrapper.Ints3 fe3 = OldFeaturesWrapper.Ints3.build(bft, r.rhs.rel, config);
      feFast3.add(fe3);
      f = new LocalFactor.Sum(fe3, f);

      // Setup write-features-to-disk
      String key = r.rhs.relName + ".outputFeatures";
      if (config.containsKey(key)) {
        File outputFeatures = config.getFile(key);
        fe3.writeFeaturesToDisk(outputFeatures, u);
      }
    }

    if (graphFeats) {
      Log.info("using graph feats");
      FeatureExtractionFactor.GraphWalks gw = new FeatureExtractionFactor.GraphWalks();
      gw.maxArgs = 6;
      f = new LocalFactor.Sum(gw, f);
    }

    if (DEBUG > 0)
      Log.info("scoreFor(" + r + "): " + f);
    assert f != LocalFactor.Constant.ZERO;
    getParameterIO().put(r, f);
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

    // Setup to write out predictions
    if (predictionsDir != null && mode != Mode.TRAIN) {
      if (!predictionsDir.isDirectory())
        predictionsDir.mkdirs();
      try {
        File f = new File(predictionsDir, dataName + ".facts.gz");
        Log.info("writing predictions to " + f.getPath());
        predictionsWriter = FileUtil.getWriter(f);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  public void finish(String dataName) {
    super.finish(dataName);
    eventCounts.increment("pass/" + mode.toString());
    Log.info("mode=" + mode + " dataName=" + dataName + "\t" + eventCounts.toString());

    if (mode == Mode.TRAIN)
      getParameterIO().saveAll();

    // Close predictions writer
    if (predictionsWriter != null) {
      try {
        predictionsWriter.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
      predictionsWriter = null;
    }

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

    boolean verbose = true;
    if (verbose || DEBUG > 1) {
      if (numArgsArg4 != null)
        Log.info("numArgsArg4 biggest weights: " + numArgsArg4.getBiggestWeights(10));
      if (numArgsArg3 != null)
        Log.info("numArgsArg3 biggest weights: " + numArgsArg3.getBiggestWeights(10));
      if (numArgsArg2 != null)
        Log.info("numArgsArg2 biggest weights: " + numArgsArg2.getBiggestWeights(10));
    }

    mode = null;
  }

  private Pred2ArgPaths.ArgCandidates tackstromArgs = null;

  private void buildTackstromArgs() {
    Sentence sent = u.dbgSentenceCache;
    if (sent == null)
      throw new IllegalStateException("call buildSentenceCacheInUberts first");
    /*
     * def tackstrom-args4 <span> <span> <token> <path>  # t, s, head of s, path from head of t to head of s
     * def observed-pa-path2 <path> <role> <count>
     *
     * MAYBE (not currently):
     * predicate2(t,f) & tackstrom-args4(t,s,shead,tspath) & observed-pa-path2(tspath,k,_) => srl2(t,s)
     */

    // Setup
    if (tackstromArgs == null) {
      // Currently tackstromArgs.getArgCandidates2 does the join on pred-arg path,
      // so the grammar only needs to include:
      //   predicate2(t,f) & tackstrom-args4(t,s,sHead,k) => argument4(t,s,f,k)
//      u.readRelData("def tackstrom-args4 <span> <span> <tokenIndex> <role>");
      ExperimentProperties config = ExperimentProperties.getInstance();
      File rolePathCounts = config.getExistingFile("rolePathCounts");
      try {
        tackstromArgs = new Pred2ArgPaths.ArgCandidates(rolePathCounts);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    // Find args
    DependencyHeadFinder hf = new DependencyHeadFinder(DependencyHeadFinder.Mode.PARSEY);
    Relation ev1 = u.getEdgeType("event1");
    for (HashableHypEdge target : u.getLabels().getGoldEdges(ev1)) {
      assert target.getEdge().getNumTails() == 1;
      Span t = Span.inverseShortString((String) target.getEdge().getTail(0).getValue());
      int thead = hf.headSafe(t, sent);
      List<Pair<Span, String>> rss = tackstromArgs.getArgCandidates2(thead, sent);
      for (Pair<Span, String> rs : rss) {
        String role = rs.get2();
        int shead = hf.head(rs.get1(), sent);
        assert shead >= 0;
        u.readRelData("x tackstrom-args4 " + t.shortString() + " " + rs.get1().shortString() + " " + shead + " " + role);
      }
    }
  }

  private Pred2ArgPaths.DepDecompArgCandidiates depDecompArgs;
  private void buildDepDecompArgs() {
    Sentence sent = u.dbgSentenceCache;
    if (sent == null)
      throw new IllegalStateException("call buildSentenceCacheInUberts first");

    // Setup
    if (depDecompArgs == null) {
      ExperimentProperties config = ExperimentProperties.getInstance();
      File p2h = new File("data/pred2arg-paths/framenet.byRole.txt");
      File h2s = new File("/tmp/a2s.txt");
      File h2e = new File("/tmp/a2e.txt");
      depDecompArgs = new Pred2ArgPaths.DepDecompArgCandidiates(p2h, h2s, h2e);
      depDecompArgs.k1 = config.getInt("depDecompArgs/k1", 10);
      depDecompArgs.k2 = config.getInt("depDecompArgs/k2", 10);
      depDecompArgs.k3 = config.getInt("depDecompArgs/k3", 10);
      depDecompArgs.c = config.getInt("depDecompArgs/c", 1);
    }

    // Find args
    DependencyHeadFinder hf = new DependencyHeadFinder(DependencyHeadFinder.Mode.PARSEY);
    Relation ev1 = u.getEdgeType("event1");
    for (HashableHypEdge target : u.getLabels().getGoldEdges(ev1)) {
      assert target.getEdge().getNumTails() == 1;
      Span t = Span.inverseShortString((String) target.getEdge().getTail(0).getValue());
      int thead = hf.headSafe(t, sent);
      List<Pair<Span, String>> rss = depDecompArgs.getArgCandidates2(thead, sent);
      for (Pair<Span, String> rs : rss) {
        String role = rs.get2();
        int shead = hf.headSafe(rs.get1(), sent);
        String f = "x dep-decomp-args4 " + t.shortString() + " " + rs.get1().shortString() + " " + shead + " " + role;
//        System.out.println(f);
        u.readRelData(f);
      }
    }
  }

  private void buildXuePalmerOTF() {
    Sentence sent = u.dbgSentenceCache;
    if (sent == null)
      throw new IllegalStateException("call buildSentenceCacheInUberts first");

    // Run arg triage
    // 1) Read predicates/targets out of the state (they should be added already)
    Relation ev1 = u.getEdgeType("event1");
    List<FrameInstance> frameMentions = new ArrayList<>();
    Set<Span> uniq = new HashSet<>();
    Set<Span> goldTargets = new HashSet<>();
    Frame f = new Frame(0, "propbank/dummyframe", null, new String[] {"ARG0", "ARG1"});
    for (HashableHypEdge target : u.getLabels().getGoldEdges(ev1)) {
      assert target.getEdge().getNumTails() == 1;
      Span t = Span.inverseShortString((String) target.getEdge().getTail(0).getValue());
      if (goldTargets.add(t) && uniq.add(t))
        frameMentions.add(FrameInstance.frameMention(f, t, sent));
    }
    FNTagging argsFor = new FNTagging(sent, frameMentions);

//    // 1b) DEBUG: Show all of the arguments that we're looking for
//    Relation a4 = u.getEdgeType("argument4");
//    for (HashableHypEdge e : u.getLabels().getGoldEdges(a4)) {
//      Span t = Span.inverseShortString((String) e.getEdge().getTail(0).getValue());
//      Span s = Span.inverseShortString((String) e.getEdge().getTail(2).getValue());
//      System.out.println("looking for: t=" + t + " s=" + s);
//    }

    // 2) Call the argument finding code
    DeterministicRolePruning drp = new DeterministicRolePruning(
        DeterministicRolePruning.Mode.XUE_PALMER_HERMANN, null, null);
//        DeterministicRolePruning.Mode.XUE_PALMER_DEP_HERMANN, null, null);
    FNParseSpanPruning args = drp.setupInference(Arrays.asList(argsFor), null).decodeAll().get(0);
    for (SpanPair ts : args.getAllArgs()) {
      Span t = ts.get1();
      Span s = ts.get2();
      if (t == Span.nullSpan || s == Span.nullSpan)
        continue;
      // OTF == "on the fly"
      u.readRelData("x xue-palmer-otf-args2 " + t.shortString() + " " + s.shortString());
    }
  }

  @Override
  public void consume(RelDoc doc) {
//    System.out.println("[consume] " + doc.getId());

    // Add xue-palmer-args2
    buildSentenceCacheInUberts();
    buildXuePalmerOTF();
    buildTackstromArgs();
//    buildDepDecompArgs();

    eventCounts.increment("consume/" + mode.toString());
    if (eventCounts.getTotalCount() % 1000 == 0) {
      System.out.println("pipeline counts: " + eventCounts.toStringWithEq());
      System.out.println("uberts counts: " + u.stats.toStringWithEq());
      System.out.println("[memLeak] " + u.getState());
    }
    switch (mode) {
    case TRAIN:
      train(doc);
      break;
    case DEV:
    case TEST:
      if (DEBUG > 0 && perfByRel.size() % 50 == 0) {
        System.out.println("mode=" + mode + " doc=" + doc.getId() + " [memLeak] perfByRel.size=" + perfByRel.size());
      }
      boolean oracle = false;
      int actionLimit = 0;

      timer.start("inf/" + mode);
      Pair<Perf, List<Step>> p = u.dbgRunInference(oracle, actionLimit);
      timer.stop("inf/" + mode);
      perfByRel.add(p.get1().perfByRel());

      // Write out predictions to a file in (many doc) fact file format with
      // comments which say gold, pred, score.
      if (predictionsWriter != null) {
        try {
          predictionsWriter.write(doc.def.toLine());
          predictionsWriter.newLine();
          for (Step s : p.get2()) {
            if (!includeNegativePredictions && !s.pred && !s.gold)
              continue;
            Adjoints a = newMarginUpdate ? s.getReason() : s.score;
            predictionsWriter.write(
                String.format("%s # gold=%s pred=%s score=%.4f",
                s.edge.getRelLine("yhat").toLine(), s.gold, s.pred, a.forwards()));
            predictionsWriter.newLine();
          }
        } catch (IOException e) {
          e.printStackTrace();
        }
      }

      if (showDevFN && mode == Mode.DEV) {

        // Steps
        System.out.println();
        for (Step s : p.get2())
          System.out.println("step: " + s);

        // True positives
        Collections.sort(p.get1().tpInstances, HypEdge.BY_RELATION_THEN_TAIL);
        System.out.println();
        for (HypEdge e : p.get1().tpInstances)
          System.out.println("prediction TP: " + e);

        // False Negatives
//        List<HypEdge> srl3FNs = p.get1().getFalseNegatives(u.getEdgeType("srl3"));
        List<HypEdge> srl3FNs = p.get1().getFalseNegatives();
        System.out.println();
        for (HypEdge e : srl3FNs)
          System.out.println("prediction FN: " + e);
      }

      ExperimentProperties config = ExperimentProperties.getInstance();
      if (DEBUG > 0 && config.getBoolean("showDevTestDetails", true)) {// && perfByRel.size() % 50 == 0) {

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

    int verbose = 0;
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
            s.getReason().backwards(lr * -1);

//            if (Uberts.LEARN_DEBUG) {
//              HashableHypEdge he = new HashableHypEdge(s.edge);
//              double d = u.dbgUpdate.getOrDefault(he, 0d);
//              u.dbgUpdate.put(he, d-1);
//            }
          }
          for (Traj cur = maxViolation.get2(); cur != null; cur = cur.getPrev()) {
            Step s = cur.getStep();
            s.getReason().backwards(lr * +1);

//            if (Uberts.LEARN_DEBUG) {
//              HashableHypEdge he = new HashableHypEdge(s.edge);
//              double d = u.dbgUpdate.getOrDefault(he, 0d);
//              u.dbgUpdate.put(he, d+1);
//            }
          }
        }

        if (Uberts.LEARN_DEBUG) {
          List<HashableHypEdge> l = new ArrayList<>(u.dbgUpdate.keySet());
          Collections.sort(l, HashableHypEdge.BY_RELATION_THEN_TAIL);
          for (HashableHypEdge e : l) {
            System.out.println("aggUpdate:  gold=" + u.getLabel(e) + "\t" + e.getEdge() + "\t" + u.dbgUpdate.get(e));
          }
          u.dbgUpdate.clear();
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
      boolean stdOld = u.showTrajDiagnostics;
      u.showTrajDiagnostics = verbose >= 2;
      int actionLimit = 0;
      if (verbose >= 1) {
        System.out.println();
        for (HypEdge e : u.getLabels().getGoldEdges())
          System.out.println("gold: " + e);
      }
      boolean oracle = u.getRandom().nextDouble() < pOracleRollIn;
      Pair<Perf, List<Step>> x = u.dbgRunInference(oracle, actionLimit);
      Perf perf = x.get1();
      List<Step> traj = x.get2();
      batch.add(lr -> {
        Map<Relation, Double> cfp = getCostFP();
        if (verbose >= 2) {
          System.out.println("about to update against length=" + traj.size()
                + " trajectory, costFP=" + cfp + " oracleRollIn=" + oracle);
        }
        for (Step s : traj) {

          // NOTE: We are NOT using minScorePerRelation here since we only want
          // to move scores about 0, not the threshold.
//          boolean pred = s.score.forwards() > 0;
          boolean pred = s.pred;
          Adjoints reason = newMarginUpdate ? s.getReason() : s.score;

          boolean a4 = s.edge.getRelation().getName().equals("argument4");

          if (s.gold && !pred) {
            if ((verbose >= 1 && a4) || verbose >= 2)
              System.out.println("FN: " + s);
            reason.backwards(-lr);
          } else if (!s.gold && pred) {
            if ((verbose >= 1 && a4) || verbose >= 2)
              System.out.println("FP: " + s);
            reason.backwards(+lr * cfp.getOrDefault(s.edge.getRelation(), 1d));
          } else if ((verbose >= 1 && a4) || verbose >= 2) {
            if (s.gold)
              System.out.println("TP: " + s);
            else if (verbose >= 2)
              System.out.println("TN: " + s);
          }
        }
      });

      // Show xue-palmer for all fn(srl2)
      if (showDevFN) {
        for (HypEdge e : perf.getFalseNegatives(u.getEdgeType("srl2"))) {
          assert e.getNumTails() == 2;
          Span t = Span.inverseShortString((String) e.getTail(0).getValue());
          Span s = Span.inverseShortString((String) e.getTail(1).getValue());
          System.out.println("didn't find arg=" + s.shortString() + " for pred=" + t.start);
          assert t.width() == 1;
          boolean orig = Pred2ArgPaths.ArgCandidates.DEBUG;
          Pred2ArgPaths.ArgCandidates.DEBUG = true;
          Pred2ArgPaths.ArgCandidates.getArgCandidates(t.start, u.dbgSentenceCache);
          Pred2ArgPaths.ArgCandidates.DEBUG = orig;
        }
      }

      // NOTE: It just occurred to me that I may be updating w.r.t. argument4
      // edges which are unreachable...
      // I don't think thats true actually, the only edges that get updated
      // are the ones that come off the agenda, and the only ones that go on the
      // agenda are those generated by the grammar; so this shouldn't include
      // gold edges which are unreachable.

      u.showTrajDiagnostics = stdOld;
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
      if (verbose >= 1)
        System.out.println();
    }
  }

}
