package edu.jhu.hlt.fnparse.inference.role.span;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.train.CrfObjective;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNIterFilters;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameRoleInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.FPR;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.role.span.DeterministicRolePruning.Mode;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.OracleStage;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.util.Counts;
import edu.jhu.hlt.fnparse.util.DataSplitReader;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.optimize.SGD;
import edu.jhu.util.Alphabet;
import edu.jhu.util.Threads;

/**
 * Implements the "classify spans" model of role id.
 * 
 * @author travis
 */
public class LatentConstituencyPipelinedParser implements Parser {
  public static final Logger LOG =
      Logger.getLogger(LatentConstituencyPipelinedParser.class);
  public static final DeterministicRolePruning.Mode DEFAULT_PRUNING_METHOD =
      Mode.XUE_PALMER_HERMANN;
  public static boolean SHOW_SPAN_RECALL = true;

  private GlobalParameters globals;
  private Stage<Sentence, FNTagging> frameId;
  private Stage<FNTagging, FNParseSpanPruning> rolePruning;
  private RoleSpanLabelingStage roleLabeling;

  // null indicates that DeterministicRolePruning is not being used,
  // RoleSpanPruningStage is.
  private DeterministicRolePruning.Mode pruningMode;

  public static LatentConstituencyPipelinedParser loadFrom(File dir) {
    throw new RuntimeException("implement me");
  }

  public LatentConstituencyPipelinedParser() {
    this.globals = new GlobalParameters();
    frameId = new OracleStage<>();
    setPruningMethod(DEFAULT_PRUNING_METHOD);
    roleLabeling = new RoleSpanLabelingStage(globals, "");
  }

  public Stage<FNTagging, FNParseSpanPruning> getPruningStage() {
    return rolePruning;
  }

  /**
   * Returns the DeterministicRolePruning.Mode if relevant, else null.
   */
  public DeterministicRolePruning.Mode getPruningMode() {
    if (rolePruning instanceof DeterministicRolePruning)
      return ((DeterministicRolePruning) rolePruning).getMode();
    else
      return null;
  }

  @Override
  public GlobalParameters getGlobalParameters() {
    return globals;
  }

  public void setPruningMethod(DeterministicRolePruning.Mode mode) {
    pruningMode = mode;
    if (mode == null) {
      rolePruning = new RoleSpanPruningStage(globals, "");
    } else {
      rolePruning = new DeterministicRolePruning(pruningMode);
    }
  }

  @Override
  public void setFeatures(String features) {
    if (frameId instanceof AbstractStage)
      ((AbstractStage<?, ?>) frameId).setFeatures(features);
    else
      LOG.warn("not setting features for frameId");
    if (rolePruning instanceof AbstractStage)
      ((AbstractStage<?, ?>) rolePruning).setFeatures(features);
    else
      LOG.warn("not setting features for rolePruning");
    if (roleLabeling instanceof AbstractStage)
      ((AbstractStage<?, ?>) roleLabeling).setFeatures(features);
    else
      LOG.warn("not setting features for roleLabeling");
  }

  public RoleSpanLabelingStage getRoleLabelingStage() {
    return roleLabeling;
  }

  public Stage<Sentence, FNTagging> getFrameIdStage() {
    return frameId;
  }

  public void setFrameIdStage(Stage<Sentence, FNTagging> s) {
    this.frameId = s;
  }

  public void loadFrameIdStage(File f) {
    try {
      InputStream is = new FileInputStream(f);
      if (f.getName().toLowerCase().endsWith(".gz"))
        is = new GZIPInputStream(is);
      DataInputStream dis = new DataInputStream(is);
      FrameIdStage fid = new FrameIdStage(globals, "");
      fid.loadModel(dis, globals);
      dis.close();
      this.frameId = fid;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void loadRoleSpanLabelingStage(File f) {
    try {
      InputStream is = new FileInputStream(f);
      if (f.getName().toLowerCase().endsWith(".gz"))
        is = new GZIPInputStream(is);
      DataInputStream dis = new DataInputStream(is);
      RoleSpanLabelingStage rsl = new RoleSpanLabelingStage(globals, "");
      rsl.loadModel(dis, globals);
      dis.close();
      this.roleLabeling = rsl;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Alphabet<String> getAlphabet() {
    return globals.getFeatureNames();
  }

  public void dontDoAnyPruning() {
    ((RoleSpanPruningStage) rolePruning).dontDoAnyPruning();
  }

  public void dontDoAnyArgId() {
    rolePruning = null;
    roleLabeling = null;
  }

  public void useDeterministicPruning(DeterministicRolePruning.Mode mode) {
    rolePruning = new DeterministicRolePruning(mode);
  }

  @Override
  public void configure(String key, String value) {
    Map<String, String> m = new HashMap<>();
    m.put(key, value);
    configure(m);
  }

  @Override
  public void configure(Map<String, String> configuration) {
    LOG.info("[configure] " + configuration);
    String key, value;

    key = "syntaxMode";
    value = configuration.get(key);
    if (value != null) {
      if ("regular".equals(value)) {
        setPruningMethod(DEFAULT_PRUNING_METHOD);
      } else {
        rolePruning = new RoleSpanPruningStage(globals, "");
      }
      LOG.info("setting " + key + " = " + value);
    }

    key = "learnFrameId";
    value = configuration.get(key);
    if (value != null) {
      LOG.info("setting " + key + " = " + value);
      if (Boolean.valueOf(value))
        frameId = new FrameIdStage(globals, null);
      else
        frameId = new OracleStage<>();
    }

    key = "skipArgId";
    value = configuration.get(key);
    if (value != null) {
      LOG.info("setting " + key + " = " + value);
      if (Boolean.valueOf(value))
        dontDoAnyArgId();
      else
        LOG.warn(key + " should never have a false value!");
    }

    key = "oneStage";
    value = configuration.get(key);
    if (value != null) {
      LOG.info("setting " + key + " = " + value);
      if (Boolean.valueOf(value)) {
        if (rolePruning instanceof RoleSpanPruningStage) {
          ((RoleSpanPruningStage) rolePruning).dontDoAnyPruning();
        } else {
          RoleSpanPruningStage rp = new RoleSpanPruningStage(globals, "");
          rp.dontDoAnyPruning();
          rolePruning = rp;
        }
      } else {
        assert !(rolePruning instanceof RoleSpanPruningStage)
          || !((RoleSpanPruningStage) rolePruning).keepingEverything();
      }
    }

    key = "features";
    value = configuration.get(key);
    if (value != null) {
      LOG.info("setting " + key + " = " + value);
      setFeatures(value);
    }

    frameId.configure(configuration);
    rolePruning.configure(configuration);
    roleLabeling.configure(configuration);
  }

  @Override
  public void train(List<FNParse> data) {
    scanFeatures(data);
    learnWeights(data);
  }

  public void scanFeatures(List<FNParse> parses) {
    LOG.info("[scanFeatures] scanning features for " + parses.size() + " parses");
    getAlphabet().startGrowth();

    frameId.scanFeatures(parses);
    if (rolePruning != null)
      rolePruning.scanFeatures(parses);
    if (roleLabeling != null)
      roleLabeling.scanFeatures(parses);

    getAlphabet().stopGrowth();
    LOG.info("[scanFeatures] done scanning features");
  }

  public void learnWeights(List<FNParse> parses) {
    LOG.info("[learnWeights] starting training on " + parses.size() + " parses");

    List<Sentence> sentences = DataUtil.stripAnnotations(parses);
    List<FNTagging> frames = DataUtil.convertParsesToTaggings(parses);
    frameId.train(sentences, frames);

    if (rolePruning != null) {
      List<FNParseSpanPruning> goldPrunes =
          FNParseSpanPruning.optimalPrune(parses);
      rolePruning.train(frames, goldPrunes);

      // TODO move this to PipelinedFnParser
      // For training this last stage, we want to interpolate between two training
      // methods:
      // A) You assume you got the heads 100% right and you want the model to try
      //    to predict the correct span for each (if it's been pruned by max,
      //    don't create a training example).
      // B) You take the predictions from RoleHeadStage, which are likely to be
      //    incorrect, and train them to recover gracefully; i.e. predict nullSpan
      //    when the head is wrong.
      //    (this presumes that the model has the capacity to tell when its wrong,
      //     which seems like an unreasonable assumption).
      // You can flip a weighted coin to decide which training method you'd like
      // to use for a given example.

      // This is not really relevant for the span model because you can't get to a
      // point where you've already committed to a mistake. You can only prune the
      // correct answer, in which case you should just drop the example. There is
      // no option to train your model to handle mistakes though.

      List<FNParseSpanPruning> hypPrunes = rolePruning
          .setupInference(frames, null).decodeAll();
      /*
      double pIncludeNegativeSpan = 0.1d;
      List<FNParseSpanPruning> hypPrunes =
        FNParseSpanPruning.noisyPruningOf(
            parses, pIncludeNegativeSpan, globals.getRandom());
      */
      roleLabeling.train(hypPrunes, parses);
    }

    LOG.info("[learnWeights] done training");
  }

  private static void showPruningProperties(String identifier, List<FNParseSpanPruning> prunes, List<FNParse> hyp, List<FNParse> gold) {

    // What is the distribution over widths of the spans that weren't pruned
    // and their relation to the predicate
    final int maxWidth = 18;  // upper bound, rest are bucketed together
    Counts<Integer> widths = new Counts<>();
    Counts<String> relToPred = new Counts<>();
    for (FNParseSpanPruning prune : prunes) {
      for (Entry<FrameRoleInstance, Set<Span>> fip : prune.getMapRepresentation().entrySet()) {
        Span target = fip.getKey().target;
        for (Span s : fip.getValue()) {
          int w = Math.min(s.width(), maxWidth);
          widths.increment(w);
          if (target.before(s))
            relToPred.increment("right");
          else if (target.after(s))
            relToPred.increment("left");
          else if (target == s)
            relToPred.increment("same");
          else if (target.overlaps(s))
            relToPred.increment("overlap");
          else
            relToPred.increment("??? WARNING!");
        }
      }
    }
    for (int w : widths.getKeysSorted()) {
      LOG.info(String.format("[showPruningProperties] %s maskSpanWidth=%2d N=%7d",
          identifier, w, widths.getCount(w)));
    }
    for (String r : relToPred.getKeysSortedByCount(true)) {
      LOG.info(String.format("[showPruningProperties] %s maskArg2pred=%9s N=%7d",
          identifier, r, relToPred.getCount(r)));
    }

    // How many elements did we let through the pruning mask?
    int totalLetThrough = 0, totalPossible = 0;
    for (FNParseSpanPruning p : prunes) {
      totalLetThrough += p.numPossibleArgs();
      totalPossible += p.numPossibleArgsNaive();
    }
    LOG.info(String.format(
        "[showPruningProperties] let through %d of %d possible arguments, %.2f%%",
        totalLetThrough, totalPossible, (100d * totalLetThrough) / totalPossible));

    // Compute recall/F1 for pruning stage
    if (gold != null) {
      FPR prunePerf = new FPR(false);
      for (int i = 0; i < gold.size(); i++) {
        FNParseSpanPruning mask = prunes.get(i);
        mask.perf(gold.get(i), prunePerf);
      }
      LOG.info("[showPruningProperties] " + identifier
          + " pruning recall=" + prunePerf.recall()
          + " f1*=" + prunePerf.f1()
          + " precision*=" + prunePerf.precision()
          + " tp+fp=" + prunePerf.tpPlusFp());
    }

    // For each FrameRoleInstance, if we included the correct span, what was
    // the precision?
    if (hyp != null) {
      FPR labelPerf = new FPR(false);
      for (int i = 0; i < gold.size(); i++) {
        FNParseSpanPruning mask = prunes.get(i);
        FNParseSpanPruning.precisionOnProperlyPrunedFrameRoleInstances(
            mask, hyp.get(i), gold.get(i), labelPerf);
      }
      LOG.info("[showPruningProperties] " + identifier
          + " labeling precision=" + labelPerf.precision()
          + " (" + labelPerf.getTP() + " / "
          + (labelPerf.getTP() + labelPerf.getFP())
          + ") tp+fp=" + labelPerf.tpPlusFp());
    }
  }

  /**
   * Takes some parses and for each realized argument a asks: which pruning methods
   * (either the latent model -- this, or a supvervised method) would have come up
   * with this span?
   *
   * The goal is to identify if there are cases where the latent syntax will recall
   * a span correctly when the supervised syntax will not.
   *
   * I'm going to flatten the representation to ignore role for now (just union over
   * all roles), but keep it sensitive to each frame/target/predicate.
   */
  public void compareLatentToSupervisedSyntax(List<FNParse> parses, DeterministicRolePruning.Mode mode, boolean verbose) {
    if (rolePruning == null || !(rolePruning instanceof RoleSpanPruningStage))
      throw new RuntimeException("only call this using a pre-trained latent model");
    LOG.info("[compareLatentToSupervisedSyntaxDebug] parses.size=" + parses.size());
    boolean mr = ((RoleSpanPruningStage) rolePruning).useCkyDecoder();
    String name = "latent-" + (mr ? "maxRecall" : "naive");
    name += " vs " + mode;
    Counts<String> counts = new Counts<>();
    Set<Span> g = new HashSet<>();  // gold
    Set<Span> h = new HashSet<>();  // hyp (latent)
    Set<Span> s = new HashSet<>();  // supervised (regular)
    List<FNParseSpanPruning> hypLatent = rolePruning.setupInference(parses, null).decodeAll();
    DeterministicRolePruning drp = new DeterministicRolePruning(mode);
    List<FNParseSpanPruning> hypSupervised = drp.setupInference(parses, null).decodeAll();
    for (int i = 0; i < parses.size(); i++) {
      FNParse p = parses.get(i);
      FNParseSpanPruning hl = hypLatent.get(i);
      FNParseSpanPruning hs = hypSupervised.get(i);
      assert hl.getSentence() == p.getSentence();
      assert hs.getSentence() == p.getSentence();
      if (verbose) {
        LOG.info("[compareLatentToSupervisedSyntax] (" + name + ") sentence: "
            + p.getSentence());
      }
      for (int fi = 0; fi < p.numFrameInstances(); fi++) {
        if (verbose) {
          LOG.info("[compareLatentToSupervisedSyntax] (" + name + ")   frameInst: "
              + Describe.frameInstance(p.getFrameInstance(fi)));
        }
        assert hl.getFrame(fi) == p.getFrameInstance(fi).getFrame();
        assert hs.getFrame(fi) == p.getFrameInstance(fi).getFrame();
        assert hl.getTarget(fi) == p.getFrameInstance(fi).getTarget();
        assert hs.getTarget(fi) == p.getFrameInstance(fi).getTarget();
        g.clear();
        h.clear();
        s.clear();
        p.getFrameInstance(fi).getRealizedArgs(g);
        h.addAll(hl.getPossibleArgs(fi));
        s.addAll(hs.getPossibleArgs(fi));
        for (Span a : g) {
          String x = String.format(
              "latent=%s regular=%s",
              h.contains(a) ? "Y" : "N",
              s.contains(a) ? "Y" : "N");
          counts.increment(x);
          counts.increment("latent=" + (h.contains(a) ? "Y" : "N"));
          counts.increment("regular=" + (s.contains(a) ? "Y" : "N"));
          if (verbose) {
            LOG.info("[compareLatentToSupervisedSyntax] (" + name + ")     "
                + x + "\t"
                + Arrays.toString(p.getSentence().getWordFor(a)));
          }
        }
      }
    }
    for (String rel : counts.getKeysSorted()) {
      LOG.info(String.format(
          "[compareLatentToSupervisedSyntax relCount] %-12s %-15s  % 5d",
          name, rel, counts.getCount(rel)));
    }
    LOG.info(String.format(
        "[compareLatentToSupervisedSyntax relCount] %-12s %-15s  % 5d",
        name, "everything", counts.getTotalCount()));
    LOG.info("");
    showPruningProperties(name, hypLatent, null, parses);
    LOG.info("");
    showPruningProperties(mode.toString(), hypSupervised, null, parses);
    LOG.info("");
  }

  @Override
  public List<FNParse> parse(List<Sentence> sentences, List<FNParse> gold) {

    long start = System.currentTimeMillis();
    List<FNTagging> frames = frameId.setupInference(sentences, gold).decodeAll();

    List<FNParse> parses;
    if (rolePruning == null) {
      LOG.info("[parse] skipping argPruning/labeling and just returning frame taggings");
      parses = DataUtil.convertTaggingsToParses(frames);
    } else {
      List<FNParseSpanPruning> goldPrune = null;
      if (gold != null)
        goldPrune = FNParseSpanPruning.optimalPrune(gold);

      List<FNParseSpanPruning> prunes =
          rolePruning.setupInference(frames, goldPrune).decodeAll();

      parses = roleLabeling.setupInference(prunes, gold).decodeAll();

      showPruningProperties(rolePruning.getName(), prunes, parses, gold);
    }

    long totalTime = System.currentTimeMillis() - start;
    if (rolePruning instanceof RoleSpanPruningStage && SHOW_SPAN_RECALL) {
      ((RoleSpanPruningStage) rolePruning).showSpanRecall();
    }
    int toks = 0;
    for (Sentence s : sentences) toks += s.size();
    LOG.info("[parse] " + (totalTime/1000d) + " sec total for "
        + sentences.size() + " sentences /" + toks + " tokens, "
        + (toks*1000d)/totalTime + " tokens per second");
    return parses;
  }

  public static final String FRAME_ID_MODEL_NAME = PipelinedFnParser.FRAME_ID_MODEL_NAME;
  public static final String ROLE_PRUNE_MODEL_NAME = "rolePrune.ser.gz";
  public static final String ROLE_LABEL_MODEL_NAME = "roleLabel.ser.gz";
  public static String ROLE_PRUNE_HUMAN_READABLE = null;
  public static String ROLE_LABEL_HUMAN_READABLE = null;

  @Override
  public void loadModel(File directory) {
    LOG.info("loading model from " + directory.getPath());
    if (!directory.isDirectory())
      throw new IllegalArgumentException();
    globals.getFeatureNames().startGrowth();
    DataInputStream dis;
    try {
      dis = Parser.getDIStreamFor(directory, FRAME_ID_MODEL_NAME);
      frameId.loadModel(dis, globals);
      dis.close();

      dis = Parser.getDIStreamFor(directory, ROLE_PRUNE_MODEL_NAME);
      rolePruning.loadModel(dis, globals);
      dis.close();

      dis = Parser.getDIStreamFor(directory, ROLE_LABEL_MODEL_NAME);
      roleLabeling.loadModel(dis, globals);
      dis.close();

      if (frameId instanceof AbstractStage)
        ((AbstractStage) frameId).showExtremeFeatures(30);
      if (rolePruning instanceof AbstractStage)
        ((AbstractStage) rolePruning).showExtremeFeatures(30);
      if (roleLabeling instanceof AbstractStage)
        ((AbstractStage) roleLabeling).showExtremeFeatures(30);

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    globals.getFeatureNames().stopGrowth();
  }

  @Override
  public void saveModel(File directory) {
    LOG.info("saving model to " + directory.getPath());
    if (!directory.isDirectory())
      throw new IllegalArgumentException();
    DataOutputStream dos;
    try {
      dos = Parser.getDOStreamFor(directory, FRAME_ID_MODEL_NAME);
      frameId.saveModel(dos, globals);
      dos.close();

      if (rolePruning != null) {
        dos = Parser.getDOStreamFor(directory, ROLE_PRUNE_MODEL_NAME);
        rolePruning.saveModel(dos, globals);
        dos.close();
        if (ROLE_PRUNE_HUMAN_READABLE != null) {
          ModelIO.writeHumanReadable(rolePruning.getWeights(), getAlphabet(),
              new File(directory, ROLE_PRUNE_HUMAN_READABLE), true);
        }
      }

      if (roleLabeling != null) {
        dos = Parser.getDOStreamFor(directory, ROLE_LABEL_MODEL_NAME);
        roleLabeling.saveModel(dos, globals);
        dos.close();
        if (ROLE_LABEL_HUMAN_READABLE != null) {
          ModelIO.writeHumanReadable(roleLabeling.getWeights(), getAlphabet(),
              new File(directory, ROLE_LABEL_HUMAN_READABLE), true);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void main(String[] args) throws Exception {
    main2(args, 0);
  }

  /** returns arg micro f1 */
  public static double main2(String[] args, int numTrainEval) throws Exception {
    if (args.length != 3) {
      System.out.println("please provide:");
      System.out.println("1) how many sentences to train on");
      System.out.println("2) a feature string");
      System.out.println("3) a working directory");
      System.exit(-1);
    }
    int nTrainLimit = Integer.parseInt(args[0]);
    String featureDesc = args[1];
    File workingDir = new File(args[2]);
    if (!workingDir.isDirectory())
      workingDir.mkdirs();
    assert workingDir.isDirectory();

    Logger.getLogger(SGD.class).setLevel(Level.ERROR);
    Logger.getLogger(Threads.class).setLevel(Level.ERROR);
    Logger.getLogger(CrfObjective.class).setLevel(Level.ERROR);
    Logger.getLogger(ConstituencyTreeFactor.class).setLevel(Level.ERROR);
    //Logger.getLogger(RoleSpanPruningStage.class).setLevel(Level.INFO);
    //BasicRoleSpanFeatures.OVERFITTING_DEBUG = true;

    LatentConstituencyPipelinedParser p =
        new LatentConstituencyPipelinedParser();
    p.setFeatures(featureDesc);
    //p.useDeterministicPruning(Mode.XUE_PALMER_HERMANN);
    //p.dontDoAnyPruning();

    // Get the data
    List<FNParse> all = DataUtil.iter2list(
        new FNIterFilters.SkipSentences<FNParse>(
            FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences(),
            Arrays.asList("FNFUTXT1274640", "FNFUTXT1279095")));
    DataSplitReader dsr = new DataSplitReader(Parser.SENTENCE_ID_SPLITS);
    List<FNParse> train = dsr.getSection(all, "train", false);
    List<FNParse> tune = dsr.getSection(all, "tune", false);
    List<FNParse> test = dsr.getSection(all, "test", false);
    if(nTrainLimit < train.size()) {
      train = DataUtil.reservoirSample(
          train, nTrainLimit, new Random(9001));
    }
    int nTestLimit = nTrainLimit;
    if (test.size() > nTestLimit) {
      test = DataUtil.reservoirSample(test, nTestLimit, new Random(9002));
    }
    LOG.info("#train=" + train.size()
        + " #tune=" + tune.size()
        + " #test=" + test.size());

    // Train a model
    p.scanFeatures(train);
    p.learnWeights(train);

    // TODO tune decoder thresholds

    // Save model
    p.saveModel(workingDir);
    ModelIO.writeHumanReadable(
        //p.rolePruning.getWeights(),
        p.roleLabeling.getWeights(),
        p.getAlphabet(),
        new File(workingDir, "constit-pruning-weights.txt.gz"),
        true);
    checkPruning(p, train);
    checkPruning(p, test);

    // Eval on test
    List<Sentence> sentences = DataUtil.stripAnnotations(test);
    List<FNParse> predicted = p.parse(sentences, test);
    Map<String, Double> results = BasicEvaluation.evaluate(test, predicted);
    BasicEvaluation.showResults("[test]", results);
    double ret = results.get("ArgumentMicroF1");

    // Eval on train
    if (numTrainEval > 0) {
      List<FNParse> trainSubset = train;
      if (train.size() > numTrainEval) {
        trainSubset = DataUtil.reservoirSample(
            train, numTrainEval, p.getGlobalParameters().getRandom());
      }
      sentences = DataUtil.stripAnnotations(trainSubset);
      predicted = p.parse(sentences, trainSubset);
      results = BasicEvaluation.evaluate(trainSubset, predicted);
      BasicEvaluation.showResults("[train]", results);
    }

    return ret;
  }

  private static void checkPruning(
      LatentConstituencyPipelinedParser parser,
      List<FNParse> examples) {
    int kept = 0, total = 0;
    FPR micro = new FPR(false);
    for (FNParse p : examples) {
      FNTagging in = DataUtil.convertParseToTagging(p);
      FNParseSpanPruning out = parser.rolePruning
          .setupInference(Arrays.asList(in), null)
          .decodeAll().get(0);
      kept += out.numPossibleArgs();
      total += out.numPossibleArgsNaive();
      FPR m = out.recall(p);
      LOG.info(String.format("%s recall=%.1f #var=%d %%kept=%.1f",
          p.getSentence().getId(),
          m.recall() * 100d,
          out.numPossibleArgs(),
          (100d * out.numPossibleArgs()) / out.numPossibleArgsNaive()));
      micro.accum(m);
    }
    LOG.info(String.format("[checkPruning] on %d examples, microRecall=%.1f"
        + " #var/parse=%.1f %%kept=%.1f",
        examples.size(),
        micro.recall() * 100d,
        ((double) kept) / examples.size(),
        (100d * kept) / total));
  }
}
