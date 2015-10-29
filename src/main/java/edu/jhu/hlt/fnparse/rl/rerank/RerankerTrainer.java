package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

import edu.jhu.hlt.fnparse.datatypes.*;
import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.PropbankReader;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SemaforEval;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.experiment.grid.ResultReporter;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures.DropoutMode;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures.PropbankFNParses;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.role.span.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.inference.role.span.DeterministicRolePruning.Mode;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.params.DecoderBias;
import edu.jhu.hlt.fnparse.rl.params.EmbeddingParams;
import edu.jhu.hlt.fnparse.rl.params.Fixed;
import edu.jhu.hlt.fnparse.rl.params.GlobalFeature;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.params.Params.Glue;
import edu.jhu.hlt.fnparse.rl.params.Params.NetworkAvg;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateful;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateless;
import edu.jhu.hlt.fnparse.rl.params.TemplatedFeatureParams;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.util.AveragedWeights;
import edu.jhu.hlt.fnparse.util.BatchProvider;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.FNDiff;
import edu.jhu.hlt.fnparse.util.LearningRateEstimator;
import edu.jhu.hlt.fnparse.util.LearningRateSchedule;
import edu.jhu.hlt.fnparse.util.PosPatternGenerator;
import edu.jhu.hlt.fnparse.util.ThresholdFinder;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.ShardUtils;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.net.NetworkParameterAveraging;
import edu.jhu.prim.tuple.Pair;

/**
 * Training logic can get out of hand (e.g. checking against dev data, etc).
 * Put all that junk in here rather than in Reranker.
 *
 * @author travis
 */
public class RerankerTrainer {
  public static final Logger LOG = Logger.getLogger(RerankerTrainer.class);
  public static boolean SHOW_FULL_EVAL_IN_TUNE = true;
  public static boolean PRUNE_DEBUG = false;

  public static ConcreteStanfordWrapper PARSER = null;

  public static enum OracleMode {
    MAX,
    MIN,
    RAND_MAX,
    RAND_MIN,
  }

  // may differ across pretrain/train
  public class Config {
    // Meta
    public final String name;

    // General parameters
    public int threads = 1;
    public int trainBeamSize = 1;
    public int testBeamSize = 1;
    public int batchSize = 1;  // If 0, compute an exact gradient (batchSize == train.size)
    public boolean batchWithReplacement = false;

    // Stopping condition
    public double stoppingTimeMinutes = 55 * 60;  // maintain this as the tightest time constraint
    public StoppingCondition stopping = new StoppingCondition.Time(stoppingTimeMinutes);
    public double stoppingConditionFrequency = 5;   // Higher means check the stopping condition less frequently, time multiple of hammingTrain

    // If true (and dev settings permit), train2 will automatically add a
    // StoppingCondition.DevSet to the list of stopping conditions.
    public boolean allowDynamicStopping = true;

    // Normally if a model has no Params.Stateful features, then getStatelessUpdate
    // is used to train the model. If this is true, getFullUpdate will always
    // be used, regardless of params.
    public boolean forceGlobalTrain = true;

    // How to implement bFunc for oracle
    // TODO Move to Reranker
    public OracleMode oracleMode = OracleMode.MAX;

    // How to prune possible argument spans
    public DeterministicRolePruning.Mode argPruningMode = Mode.XUE_PALMER_HERMANN;

    // Learning rate estimation parameters
    public LearningRateSchedule learningRate = new LearningRateSchedule.Normal(1);
    public double estimateLearningRateFreq = 8;       // Higher means estimate less frequently, time multiple of hammingTrain, <=0 means disabled
    public int estimateLearningRateGranularity = 3;   // Must be odd and >2, how many LR's to try
    public double estimateLearningRateSpread = 8;     // Higher means more spread out
    public int estimateLearningRateSteps = 10;        // How many batche steps to take when evaluating a lr
    public int estimateLearningRateDevLimit = 30;     // Size of dev set for evaluating improvement, also limited by the amount of dev data

    // F1-Tuning parameters
    private double propDev = 0.2d;
    private int maxDev = 750;
    public StdEvalFunc objective = BasicEvaluation.argOnlyMicroF1;
    public double recallBiasLo = -1, recallBiasHi = 1;
    public int tuneSteps = 5;
    public boolean tuneOnTrainingData = false;

    // Convenient extras
    public final File workingDir;
    public final Random rand;
    public Consumer<Integer> calledEveryEpoch = i -> {};
    public boolean performTuning() { return propDev > 0 && maxDev > 0; }
    public void dontPerformTuning() { propDev = 0; maxDev = 0; }
    public void spreadTuneRange(double factor) {
      assert factor > 0;
      assert recallBiasLo < 0;
      assert recallBiasHi > 0;
      recallBiasLo *= factor;
      recallBiasHi *= factor;
    }

    // Prints out a bunch of stuff.
    public boolean debugNetworkParamAveraging = false;

    // Timers for how long stuff has taken.
    public Timer tHammingTrain = new Timer("hammingTrain", 10, false);
    public Timer tLearningRateEstimation = new Timer("learningRateEstimation", 1, false);
    public Timer tStoppingCondition = new Timer("stoppingCondition", 1, false);

    public Config(String name, File workingDir, Random rand) {
      if (!workingDir.isDirectory())
        throw new IllegalArgumentException();
      this.name = name;
      this.workingDir = workingDir;
      this.rand = rand;
    }

    public void setPropDev(double propDev) {
      this.maxDev = Integer.MAX_VALUE;
      this.propDev = propDev;
    }

    public void setMaxDev(int maxDev) {
      this.maxDev = maxDev;
      this.propDev = 0.99d;
    }

    public void autoPropDev(int nTrain) {
      int nDev = (int) Math.pow(nTrain, 0.7d);
      setMaxDev(nDev);
    }

    public void scaleLearningRateToBatchSize(int batchSizeWithLearningRateOf1) {
      if (batchSize == 0) {
        Log.warn("[scaleLearningRateToBatchSize] batchSize=0 (exact gradient), "
            + "so leaving learning rate as-is: " + learningRate);
        return;
      }
      double f = Math.sqrt(batchSize) / Math.sqrt(batchSizeWithLearningRateOf1);
      Log.info("[scaleLearningRateToBatchSize] scaling learning rate of name="
          + name + " by factor=" + f);
      learningRate.scale(f);
    }

    /**
     * After this method, will stop if the previous stopping condition says so
     * OR if the given stopping condition does.
     * @return what is passed in.
     */
    public <T extends StoppingCondition> T addStoppingCondition(T s) {
      if (!allowDynamicStopping && !(s instanceof StoppingCondition.Time))
        throw new RuntimeException("can't add " + s);
      if (s instanceof StoppingCondition.Time) {
        double t = ((StoppingCondition.Time) s).getMaxMinutes();
        if (t < stoppingTimeMinutes) {
          Log.info("[main] stoppingTimeMin just got tighter: " + stoppingTimeMinutes + " => " + t);
          stoppingTimeMinutes = t;
        }
      }
      stopping = new StoppingCondition.Conjunction(stopping, s);
      return s;
    }

    public String toString() {
      return "(Config " + name
          + " threads=" + threads
          + " beam=" + trainBeamSize + "/" + testBeamSize
          + " batch=" + batchSize
          + " stopping=" + stopping
          + " learningRate=" + learningRate
          + " tune=" + performTuning()
          + " objective=" + objective.getName()
          + " propDev=" + propDev
          + " maxDev=" + maxDev
          + " tuneSteps=" + tuneSteps
          + " tuneOnTrain=" + tuneOnTrainingData
          + ")";
    }
  }

  // Configuration
  private MultiTimer timer = new MultiTimer();
  public final Random rand;
  public List<ResultReporter> reporters;
  public Config pretrainConf; // for training statelessParams
  public Config trainConf;    // for training statelessParams + statefulParams
  public boolean performPretrain = false;

  // If true, use DeterministicRolePruning to cut down the set of (t,k,spans)
  // that are considered ahead of time. Requires that parsing be in effect.
  // TODO Move to DeterministicRolePruning.Mode and Reranker.argPruningMode
  public boolean useSyntaxSpanPruning = true;

  public static final Predicate<Sentence> KEEP_ALL = s -> true;
  public Predicate<Sentence> keep = KEEP_ALL;

  // Model parameters
  public Params.Stateful statefulParams = Stateful.NONE;
  public Params.Stateless statelessParams = Stateless.NONE;
  public Params.PruneThreshold tauParams = Params.PruneThreshold.Const.ONE;

  // For model averaging over the network
  public String parameterServerHost = null;
  public NetworkParameterAveraging.Server parameterServer;
  public NetworkParameterAveraging.Client parameterServerClient;
  /**
   * This will be a Sum, which will have pointers to the stateless, stateful,
   * and tau components. This sum will not be used to predict anything (as it
   * is a mix of params for different purposes), but it can be used as a struct
   * of all the parameter pieces, and can be used to deserialize everything.
   */
  public Params.NetworkAvg networkParams;   // wrapper around Params.Glue

  /**
   * {@link CachedFeatures} forms a module which is used in more than one place
   * (e.g. as an {@link ItemProvider} and as for {@link DeterministicRolePruning}.
   * As such, it needs to be used in multiple parts of the setup and is difficult
   * to pass from function to function. If this field is non-null, then every usage
   * of this module should be assumed to in use.
   */
  public CachedFeatures cachedFeatures;

  // For debugging
  public boolean bailOutOfTrainingASAP = false;


  public RerankerTrainer(Random rand, File workingDir) {
    if (!workingDir.isDirectory())
      throw new IllegalArgumentException();
    this.rand = rand;
    File pwd = new File(workingDir, "wd-pretrain");
    if (!pwd.isDirectory())
      pwd.mkdir();
    this.pretrainConf = new Config("pretrain", pwd, rand);
    this.pretrainConf.trainBeamSize = 1;
    this.pretrainConf.testBeamSize = 1;
    File twd = new File(workingDir, "wd-train");
    if (!twd.isDirectory())
      twd.mkdir();
    this.trainConf = new Config("train", twd, rand);
    this.performPretrain = false;
  }

  /** Returns what you passed in */
  public <T extends Params.Stateful> T addGlobalParams(T p) {
    if (p == null)
      throw new IllegalArgumentException();
    if (this.statefulParams == Params.Stateful.NONE)
      this.statefulParams = p;
    else
      this.statefulParams = new Params.SumStateful(this.statefulParams, p);
    return p;
  }

  /** Returns what you passed in */
  public <T extends Params.Stateless> T addStatelessParams(T p) {
    if (p == null)
      throw new IllegalArgumentException();
    if (this.statelessParams == Params.Stateless.NONE)
      this.statelessParams = p;
    else
      this.statelessParams = new Params.SumStateless(this.statelessParams, p);
    return p;
  }

  /** Returns what you passed in */
  public <T extends Params.PruneThreshold> T addPruningParams(T p) {
    if (p == null)
      throw new IllegalArgumentException();
    this.tauParams = new Params.PruneThreshold.Sum(this.tauParams, p);
    return p;
  }

  /** If you don't want anything to print, just provide showStr=null,diffArgsFile=null */
  public static Map<String, Double> eval(
      Reranker m,
      Config conf,
      ItemProvider ip,
      File semaforEvalDir,
      String showStr,
      File diffArgsFile,
      File predictionsFile) {

    // Make predictions
    ExecutorService es = conf.threads > 1
      ? Executors.newWorkStealingPool(conf.threads)
      : Executors.newSingleThreadExecutor();
    List<Future<Pair<FNParse, FNParse>>> futures = new ArrayList<>();
    int n = ip.size();
    Log.info("[main] using " + conf.threads + " threads to evaluate " + n + " parses");
    for (int i = 0; i < n; i++) {
      final int ii = i;
      futures.add(es.submit(() -> {
        FNParse y = ip.label(ii);
        State init = m.getInitialStateWithPruning(y, y);
        FNParse yhat = m.predict(init);
        return new Pair<>(y, yhat);
      }));
    }
    TimeMarker tm = new TimeMarker();
    List<FNParse> y = new ArrayList<>();
    List<FNParse> yHat = new ArrayList<>();
    for (Future<Pair<FNParse, FNParse>> f : futures) {
      try {
        Pair<FNParse, FNParse> yyhat = f.get();
        y.add(yyhat.get1());
        yHat.add(yyhat.get2());

        if (tm.enoughTimePassed(15)) {
          Log.info("[main] parsed " + y.size()
            + " in " + tm.secondsSinceFirstMark() + " seconds");
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    try {
      Log.info("shutting down ES for test predictions");
      es.shutdown();
    } catch (Exception e) {
      e.printStackTrace();
    }

    // Make predictions
//    List<FNParse> y = ItemProvider.allLabels(ip);
//    List<State> initialStates = new ArrayList<>();
//    for (FNParse p : y) initialStates.add(m.getInitialStateWithPruning(p, p));
//    List<FNParse> yHat = m.predict(initialStates);

    // Call standard evaluation functions
    Map<String, Double> results = BasicEvaluation.evaluate(y, yHat);
    if (showStr != null)
      BasicEvaluation.showResults(showStr, results);
    if (diffArgsFile != null) {
      try {
        writeErrors(y, yHat, diffArgsFile);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    // Use semafor's evaluation script
    if (semaforEvalDir != null) {
      Log.info("[eval] calling semafor eval in " + semaforEvalDir.getPath());
      if (!semaforEvalDir.isDirectory())
        throw new IllegalArgumentException();
      try {
        SemaforEval semEval = new SemaforEval(semaforEvalDir);
        semEval.evaluate(y, yHat, new File(semaforEvalDir, "results.txt"));
      } catch (Exception e) {
        Log.warn("semafor evaluation failed! semaforEvalDir"
            + semaforEvalDir.getPath() + " showStr=" + showStr);
        e.printStackTrace();
      }
    }

    // Write out predictions into a text file
    if (predictionsFile != null) {
      writePredictions(yHat, predictionsFile);
    } else {
      Log.info("[main] not writing out predictions because of null arg");
    }

    return results;
  }

  public static void writePredictions(List<FNParse> predictions, File f) {
    Log.info("[main] writing " + predictions.size() + " predictions to " + f.getPath());
    try (BufferedWriter w = FileUtil.getWriter(f)) {
      for (FNParse p : predictions) {
        String id = p.getSentence().getId();
        int T = p.numFrameInstances();
        for (int t = 0; t < T; t++) {
          FrameInstance fi = p.getFrameInstance(t);
          String fn = fi.getFrame().getName();
          Span ts = fi.getTarget();
          int K = fi.getFrame().numRoles();
          for (int k = 0; k < K; k++) {
            Span as = fi.getArgument(k);
            String rn = fi.getFrame().getRole(k);
            w.write(String.format("%s\t%d\t%s\t%d,%d\t%d\t%s\t%d,%d\n",
              id, t, fn, ts.start, ts.end, k, rn, as.start, as.end));
          }
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void writeErrors(List<FNParse> gold, List<FNParse> hyp, File f) throws IOException {
    if (gold.size() != hyp.size())
      throw new IllegalArgumentException();
    FileWriter fw = new FileWriter(f);
    int n = gold.size();
    for (int i = 0; i < n; i++) {
      String diff = FNDiff.diffArgs(gold.get(i), hyp.get(i), true);
      fw.write(diff);
      fw.write("\n");
    }
    fw.close();
  }

  public static double eval(Reranker m, Config conf, ItemProvider ip, StdEvalFunc objective) {
    String showStr = null;
    File diffArgsFile = null;
    File predictionsFile = null;
    File semEvalDir = null;
    Map<String, Double> results = eval(m, conf, ip, semEvalDir, showStr, diffArgsFile, predictionsFile);
    return results.get(objective.getName());
  }

  /**
   * Inserts an extra bias Param.Stateless into the given model and tunes it
   * @return the F1 on the dev set of the selected recall bias.
   */
  private double tuneModelForF1(Reranker model, Config conf, ItemProvider dev) {
    // Insert the bias into the model
    Params.PruneThreshold tau = model.getPruningParams();
    DecoderBias bias = new DecoderBias();
    model.setPruningParams(new Params.PruneThreshold.Sum(bias, tau));

    // Make a function which computes the dev set loss given a threshold
    Function<Double, Double> thresholdPerf = new Function<Double, Double>() {
      @Override
      public Double apply(Double threshold) {
        timer.start("tuneModelForF1.eval");
        Log.info(String.format("[tuneModelForF1] trying out recallBias=%+5.2f", threshold));
        bias.setRecallBias(threshold);
        File diffArgsFile = null;
        File predictionsFile = null;
        File semEvalDir = null;
        String msg = SHOW_FULL_EVAL_IN_TUNE
              ? String.format("[tune recallBias=%.2f]", bias.getRecallBias()) : null;
        Map<String, Double> results = eval(
          model, conf, dev, semEvalDir, msg, diffArgsFile, predictionsFile);
        double perf = results.get(conf.objective.getName());
        Log.info(String.format("[tuneModelForF1] recallBias=%+5.2f perf=%.3f",
            bias.getRecallBias(), perf));
        timer.stop("tuneModelForF1.eval");
        return perf;
      }
    };

    // Encourage the search to stay near 0 if it doesn't make much of a
    // difference to F1
    Function<Double, Double> thresholdPerfR = new Function<Double, Double>() {
      @Override
      public Double apply(Double threshold) {
        double f1 = thresholdPerf.apply(threshold);
        return f1 - (Math.abs(threshold) / 200);  // threshold=3 => penalty 0.015
      }
    };

    // Let ThresholdFinder do the heavy lifting
    Pair<Double, Double> best = ThresholdFinder.search(
        thresholdPerfR, conf.recallBiasLo, conf.recallBiasHi, conf.tuneSteps);

    // Log and set the best value
    Log.info("[tuneModelForF1] chose recallBias=" + best.get1()
        + " with " + conf.objective.getName() + "=" + best.get2());
    bias.setRecallBias(best.get1());
    return best.get2();
  }

  public Reranker instantiate() {
    assert pretrainConf.argPruningMode == trainConf.argPruningMode;
    assert pretrainConf.trainBeamSize == trainConf.trainBeamSize;
    assert pretrainConf.testBeamSize == trainConf.testBeamSize;
    return new Reranker(
        statefulParams,
        statelessParams,
        tauParams,
        trainConf.argPruningMode,
        cachedFeatures,
        pretrainConf.trainBeamSize,
        pretrainConf.testBeamSize,
        rand);
  }

  /** Trains and tunes a full model */
  public Reranker train1(ItemProvider train, ItemProvider dev) {
    if (statefulParams == Stateful.NONE && statelessParams == Stateless.NONE)
      throw new IllegalStateException("you need to set the params");

    Reranker m = instantiate();

    if (bailOutOfTrainingASAP)
      Log.info("bailing out of training ASAP -- for debugging");

    if (performPretrain && !bailOutOfTrainingASAP) {
      Log.warn("[main] you probably don't want pretrain/local train!");
      Log.info("[main] local train");
      train2(m, train, dev, pretrainConf);
    } else {
      Log.info("[main] skipping pretrain");
    }

    Log.info("[main] global train");
    m.setStatefulParams(statefulParams);
    if (!bailOutOfTrainingASAP)
      train2(m, train, dev, trainConf);

    Log.info("[main] done, times:\n" + timer);
    return m;
  }

  /**
   * WARNING: Will clobber a learning rate and replace it with Constant. That said
   * it will use the current learning rate as a jumping off point.
   */
  public void estimateLearningRate(Reranker m, ItemProvider train, ItemProvider dev, Config conf) {
    // Set a new learning rate so that we know exactly what we're dealing with.
    LearningRateSchedule lrOld = conf.learningRate;
    double lrOldV = lrOld.learningRate();
    conf.learningRate = new LearningRateSchedule.Constant(lrOldV);
    Log.info("[estimateLearningRate] starting at lr=" + lrOldV);

    // Create the directory that model params will be saved into
    final File paramDir = new File(conf.workingDir, "lrEstParams");
    if (!paramDir.isDirectory())
      paramDir.mkdir();
    assert paramDir.isDirectory();

    // Only use some of the dev data for estimating the learning rate
    final ItemProvider devSmall;
    if (dev.size() > conf.estimateLearningRateDevLimit) {
      Log.info("[estimateLearningRate] chopping down dev down: "
          + dev.size() + " => " + conf.estimateLearningRateDevLimit);
      Random r = new Random(9001);  // don't want this to vary: same split every time
      devSmall = new ItemProvider.Slice(dev, conf.estimateLearningRateDevLimit, r);
    } else {
      devSmall = dev;
    }

    // Make an adapter for LearningRateEstimator
    LearningRateEstimator.Model model = new LearningRateEstimator.Model() {
      private DoubleSupplier devLoss = modelLossOnData(m, devSmall, conf);
      private int seed = conf.rand.nextInt();
      @Override
      public void train() {
        try {
          // We want every rollout to use the same batches to reduce variance.
          Random r = new Random(seed);
          BatchProvider bp = new BatchProvider(r, train.size());
          for (int i = 0; i < conf.estimateLearningRateSteps; i++) {
            List<Integer> batch = bp.getBatch(conf.batchSize, conf.batchWithReplacement);
            hammingTrainBatch(m, batch, null, train, conf, -1, "lrEst");
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      @Override
      public void setLearningRate(double learningRate) {
        conf.learningRate = new LearningRateSchedule.Constant(learningRate);
      }
      public File getFileFor(String name) {
        return new File(paramDir, name);
      }
      @Override
      public void saveParams(String identifier) {
        try {
          File f = getFileFor(identifier);
          DataOutputStream dos = new DataOutputStream(new FileOutputStream(f));
          m.serializeParams(dos);
          dos.close();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      @Override
      public void loadParams(String identifier) {
        try {
          File f = getFileFor(identifier);
          DataInputStream dis = new DataInputStream(new FileInputStream(f));
          m.deserializeParams(dis);
          dis.close();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      @Override
      public double loss() {
        return devLoss.getAsDouble();
      }
      @Override
      public double getLearningRate() {
        return conf.learningRate.learningRate();
      }
    };

    // Find the best learning rate
    int queries = conf.estimateLearningRateGranularity;
    double spread = conf.estimateLearningRateSpread;
    LearningRateEstimator.estimateLearningRate(model, queries, spread);

    // Clean up
    FileUtil.rm_rf(paramDir);
  }

  private static class DSResult {
    public final double loss;
    public final FNParse y;
    public final FNParse yhat;
    public DSResult(double loss, FNParse y, FNParse yhat) {
      this.loss = loss;
      this.y = y;
      this.yhat = yhat;
    }
  }

  private DoubleSupplier modelLossOnData(Reranker m, ItemProvider dev, Config conf) {
    return new DoubleSupplier() {
      final boolean showAllLosses = ExperimentProperties.getInstance()
        .getBoolean("showDevLossOther", false);
      final int earlyShow = ExperimentProperties.getInstance().getInt("showDevLossOther.earlyInterval", 20);
      @Override
      public double getAsDouble() {
        Log.info("[devLossFunc] computing dev set loss on " + dev.size() + " examples");

        // Turn off dropout when you make predictions
        CachedFeatures.Params w = m.cachedFeatures.params;
        boolean resetDropoutModeToTrain = false;
        if (w.getDropoutMode() == DropoutMode.TRAIN) {
          Log.info("[devLossFunc] about to toggle dropout mode for predictions");
          resetDropoutModeToTrain = true;
          w.setDropoutMode(DropoutMode.TEST);
        }

        ExecutorService es = conf.threads > 1
          ? Executors.newWorkStealingPool(conf.threads)
          : Executors.newSingleThreadExecutor();

        List<Future<DSResult>> futures = new ArrayList<>();
        int n = dev.size();
        for (int i = 0; i < n; i++) {
          final int ii = i;
          futures.add(es.submit(() -> {
            FNParse y = dev.label(ii);
            State init = useSyntaxSpanPruning
              ? m.getInitialStateWithPruning(y, y)
              : State.initialState(y, dev.items(ii));
            Update u = m.hasStatefulFeatures() || conf.forceGlobalTrain
              ? m.getFullUpdate(init, y, conf.oracleMode, conf.rand, null, null)
              : m.getStatelessUpdate(init, y);
            double loss = u.violation();
            assert Double.isFinite(loss) && !Double.isNaN(loss);

            // Requires me to do prediction (slower) => optional
            if (showAllLosses) {
              FNParse yhat = m.predict(init);
              return new DSResult(loss, y, yhat);
            } else {
              return new DSResult(loss, y, null);
            }
          }));
        }

        int i = 0;
        double loss = 0d;
        Map<String, FPR> other = new HashMap<>();
        for (Future<DSResult> f : futures) {
          try {
            DSResult p = f.get();
            loss += p.loss;
            if (showAllLosses)
              BasicEvaluation.updateEvals(new SentenceEval(p.y, p.yhat), other, true);
            // Print early!
            if (earlyShow > 0 && i > 0 && n > earlyShow * 3 && i % earlyShow == 0) {
              Log.info("[devLossFunc] after " + i + " updates, avgLoss=" + (loss/i));
              if (showAllLosses) {
                for (Map.Entry<String, FPR> x : other.entrySet())
                  Log.info("[devLossFunc] after " + i + " updates " + x.getKey() + "=" + x.getValue());
              }
            }
            i++;
          } catch (Exception e) {
            e.printStackTrace();
          }
        }

        try {
          Log.info("shutting down ES");
          es.shutdown();
        } catch (Exception e) {
          e.printStackTrace();
        }

        assert i > 0;
        if (i < n)
          Log.warn("only got " + i + " of " + n + " dev set items!");
        loss /= i;
        Log.info("[devLossFunc] loss=" + loss + " n=" + i + " nDev=" + n + " for conf=" + conf.name);
        if (showAllLosses) {
          for (Map.Entry<String, FPR> x : other.entrySet())
            Log.info("[devLossFunc] " + x.getKey() + "=" + x.getValue());
        }

        if (resetDropoutModeToTrain) {
          Log.info("[devLossFunc] about to toggle dropout back to how it was");
          w.setDropoutMode(DropoutMode.TRAIN);
        }

        return loss;
      }
    };
  }

  /**
   * Adds a stopping condition based on the dev set performance.
   */
  public void train2(Reranker m, ItemProvider train, ItemProvider dev, Config conf) {
    /*
    // Split the data
    final ItemProvider train, dev;
    if (conf.tuneOnTrainingData) {
      Log.info("[main] tuneOnTrainingData=true");
      train = ip;
      dev = new ItemProvider.Slice(ip, Math.min(ip.size(), conf.maxDev), rand);
    } else {
      Log.info("[main] tuneOnTrainingData=false, splitting data");
      conf.autoPropDev(ip.size());
      ItemProvider.TrainTestSplit trainDev =
          new ItemProvider.TrainTestSplit(ip, conf.propDev, conf.maxDev, rand);
      train = trainDev.getTrain();
      dev = trainDev.getTest();
    }
    */
    Log.info("[main] nTrain=" + train.size() + " nDev=" + dev.size() + " for conf=" + conf.name);

    // Use dev data for stopping condition
    StoppingCondition.DevSet dynamicStopping = null;
    if (conf.allowDynamicStopping) {
      if (dev.size() == 0)
        throw new RuntimeException("no dev data!");
      Log.info("[main] adding dev set stopping on " + dev.size() + " examples");
      File rScript = new File("scripts/stop.sh");
      double alpha = 0.05d;   // Lower numbers mean stop earlier.
      double k = 7;           // Size of history
      int skipFirst = 2;      // Drop the first value(s) to get the variance est. right.
      DoubleSupplier devLossFunc = modelLossOnData(m, dev, conf);
      dynamicStopping = conf.addStoppingCondition(
          new StoppingCondition.DevSet(rScript, devLossFunc, alpha, k, skipFirst));
    } else {
      Log.info("[main] allowDynamicStopping=false leaving stopping condition as is");
    }

    try {
      // Train the model
      hammingTrain(m, train, dev, conf);
      Log.info("[main] done hammingTrain, params:");
      m.showWeights();

      // Tune the model
      if (conf.performTuning()) {
        tuneModelForF1(m, conf, dev);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // StoppingCondition.DevSet writes to a file, this closes that.
    if (dynamicStopping != null)
      dynamicStopping.close();
    Log.info("[main] done conf=" + conf.name);
    Log.info("[main] times: " + timer);
    Log.info("[main] totally done conf=" + conf.name);
  }

  // Only relevant to hammingTrain
  public double secsBetweenShowingWeights = 0.5 * 60d;

  /**
   * Trains the model to minimize hamming loss. If onlyStatelss is true, then
   * Stateful params of the model will not be fit, but updates should be much
   * faster to solve for as they don't require any forwards/backwards pass.
   */
  public void hammingTrain(Reranker r, ItemProvider train, ItemProvider dev, Config conf)
      throws InterruptedException, ExecutionException {
    Log.info("[main] starting, conf=" + conf);
    String timerStr = "hammingTrain." + conf.name;
    timer.start(timerStr);

    BatchProvider batchProvider = new BatchProvider(conf.rand, train.size());

    ExecutorService es = null;
    if (conf.threads > 1) {
      assert r.cachedFeatures != null : "regular features are not thread safe";
      Log.info("Using multi-threading threads=" + conf.threads);
      es = Executors.newWorkStealingPool(conf.threads);
    }

    // Subtract off the time spent before the first stopping condition evaluation,
    // or else the first stopping condition evaluation will fire like 3 times in a row.
    long start = System.currentTimeMillis();
    long firstStopCondEval = -1;

    TimeMarker t = new TimeMarker();
    double minSecOfTrainingBeforeLrEst = 30 * 60;   // otherwise wait for at least an epoch
    boolean showTime = false;
    boolean showViolation = true;
    double violationRunningAvg = 1.0;
    double violationRunningAvgLambda = 0.9;
    int epoch = 0;
    outer:
    for (int iter = 0; true; ) {
      int step = conf.batchSize == 0 ? train.size() : conf.batchSize;
      Log.info("[main] epoch=" + epoch + " iter=" + iter
          + " train.size=" + train.size() + " step=" + step
          + " lr=" + conf.learningRate
          + " threads=" + conf.threads);
      for (int i = 0; i < train.size(); i += step) {

        // Batch step
        conf.tHammingTrain.start();
        List<Integer> batch = batchProvider.getBatch(conf.batchSize, conf.batchWithReplacement);
        double violation = hammingTrainBatch(r, batch, es, train, conf, iter, timerStr);
        conf.tHammingTrain.stop();
        violationRunningAvg =violationRunningAvgLambda * violationRunningAvg
            + (1 - violationRunningAvgLambda) * violation;

        if (showViolation && iter % 10 == 0) {
          Log.info("[main] i=" + i + " iter=" + iter
              + " trainViolation=" + violation
              + " trainViolationAvg=" + violationRunningAvg
              + " lrVal=" + conf.learningRate.learningRate()
              + " threads=" + conf.threads
              + " " + Describe.memoryUsage());
        }

        // Print some data every once in a while.
        // Nothing in this conditional should have side-effects on the learning.
        if (t.enoughTimePassed(secsBetweenShowingWeights)) {
          r.showWeights();
          if (showTime) {
            Timer bt = timer.get(timerStr + ".batch", false);
            int totalUpdates = conf.stopping.estimatedNumberOfIterations();
            Log.info(String.format(
                "[main] estimate: completed %d of %d updates, %.1f minutes remaining",
                bt.getCount(), totalUpdates, bt.minutesUntil(totalUpdates)));
          }
        }

        // See if we should stop
        long pre = (firstStopCondEval > 0 ? firstStopCondEval : System.currentTimeMillis()) - start;
        double tStopRatio = (conf.tHammingTrain.totalTimeInSeconds() - pre/1000d)
            / conf.tStoppingCondition.totalTimeInSeconds();
        Log.info("train/stop=" + tStopRatio
            + " threshold=" + conf.stoppingConditionFrequency);
        if (tStopRatio > conf.stoppingConditionFrequency
            && (epoch > 0 || iter > 100 || t.secondsSinceFirstMark() > (conf.stoppingTimeMinutes * 60 / 3))) {
          Log.info("[main] evaluating the stopping condition");
          firstStopCondEval = System.currentTimeMillis();
          conf.tStoppingCondition.start();
          boolean stop = conf.stopping.stop(iter, violation);
          conf.tStoppingCondition.stop();
          if (stop) {
            Log.info("[main] stopping due to " + conf.stopping);
            break outer;
          }
        }

        // See if we should re-estimate the learning rate.
        if (conf.estimateLearningRateFreq > 0) {
          double tLrEstRatio = conf.tHammingTrain.totalTimeInSeconds()
              / conf.tLearningRateEstimation.totalTimeInSeconds();
          Log.info("train/lrEstimate=" + tLrEstRatio
              + " threshold=" + conf.estimateLearningRateFreq);
          if (tLrEstRatio > conf.estimateLearningRateFreq &&
              (epoch > 0 || t.secondsSinceFirstMark() > minSecOfTrainingBeforeLrEst)) {
            Log.info("[main] restimating the learning rate");
            conf.tLearningRateEstimation.start();
            estimateLearningRate(r, train, dev, conf);
            conf.tLearningRateEstimation.stop();
          }
        } else {
          Log.info("not restimating learning rate");
        }

        // Average parameters over the network
        if (parameterServerClient != null) {
          assert networkParams != null;
          if (conf.debugNetworkParamAveraging) {
            System.out.println();
            System.out.println();
            Log.info("statelessMatch=" + (statelessParams == r.getStatelessParams()));
            Log.info("statefulMatch=" + (statefulParams == r.getStatefulParams()));
            Log.info("tauMatch=" + (tauParams == r.getPruningParams()));
            System.out.println();
            Log.info("paramServerClient.getParams=" + parameterServerClient.getParams());
            NetworkAvg na = (NetworkAvg) parameterServerClient.getParams();
            Log.info("paramServerClient sum:");
            na.get().showWeights();
            if (na.getNumAdds() > 0) {
              Log.info("paramServerClient avg:");
              na.getAverage().showWeights();
            }
            System.out.println();
            Glue g = (Glue) na.getRawSum();
            Log.info("statelessMatchGlue=" + (statelessParams == g.getStateless()));
            Log.info("statefulMatchGlue=" + (statefulParams == g.getStateful()));
            Log.info("tauMatchGlue=" + (tauParams == g.getTau()));
            System.out.println();
            Log.info("model stateful params:");
            r.getStatefulParams().showWeights();
            Log.info("model stateless params:");
            r.getStatelessParams().showWeights();
            Log.info("model tau params:");
            r.getPruningParams().showWeights();
            System.out.println();
            System.out.println();
          }
          parameterServerClient.paramsChanged();
        }

        iter++;
      }
      conf.calledEveryEpoch.accept(iter);
      epoch++;
    }
    if (es != null)
      es.shutdown();

    Log.info("[main] telling Params that training is over");
    r.getStatelessParams().doneTraining();
    r.getStatefulParams().doneTraining();
    r.getPruningParams().doneTraining();

    Log.info("[main] times:\n" + timer);
    timer.stop(timerStr);
  }

  /**
   * Returns the average violation over this batch.
   */
  private double hammingTrainBatch(
      Reranker r,
      List<Integer> batch,
      ExecutorService es,
      ItemProvider ip,
      Config conf,
      int iter,
      String timerStrPartial) throws InterruptedException, ExecutionException {
    Timer tmv = timer.get(timerStrPartial + ".mostViolated", true).setPrintInterval(10).ignoreFirstTime();
    Timer to = timer.get(timerStrPartial + ".oracle", true).setPrintInterval(10).ignoreFirstTime();
    Timer t = timer.get(timerStrPartial + ".batch", true).setPrintInterval(10).ignoreFirstTime();
    t.start();

    // Compute updates for the batch
    boolean verbose = false;
    List<Update> finishedUpdates = new ArrayList<>();
    if (es == null) {
      if (verbose)
        Log.info("[hammingTrainBatch] running serial");
      for (int idx : batch) {
        if (verbose)
          Log.info("[hammingTrainBatch] submitting " + idx);
        FNParse y = ip.label(idx);
        State init = useSyntaxSpanPruning
            ? r.getInitialStateWithPruning(y, y)
            : State.initialState(y, ip.items(idx));
        Update u = r.hasStatefulFeatures() || conf.forceGlobalTrain
          ? r.getFullUpdate(init, y, conf.oracleMode, rand, to, tmv)
          : r.getStatelessUpdate(init, y);
        finishedUpdates.add(u);
      }
    } else {
      List<Future<Update>> futures = new ArrayList<>(batch.size());
      for (int idx : batch) {
        futures.add(es.submit( () -> {
          FNParse y = ip.label(idx);
          State init = useSyntaxSpanPruning
            ? r.getInitialStateWithPruning(y, y)
            : State.initialState(y, ip.items(idx));
          return r.hasStatefulFeatures() || conf.forceGlobalTrain
            ? r.getFullUpdate(init, y, conf.oracleMode, rand, to, tmv)
            : r.getStatelessUpdate(init, y);
        } ));
      }
      for (Future<Update> f : futures)
        finishedUpdates.add(f.get());
    }
    if (verbose)
      Log.info("[hammingTrainBatch] applying updates");
    assert finishedUpdates.size() == batch.size();

    // Apply the updates
    double learningRate = conf.learningRate.learningRate();
    double violation = new Update.Batch<>(finishedUpdates).apply(learningRate);
    conf.learningRate.observe(iter, violation, conf.batchSize);
    t.stop();
    return violation;
  }

  public static void computeShape(ItemProvider ip) {
    long t = System.currentTimeMillis();
    for (FNParse y : ip) {
      Sentence s = y.getSentence();
      int n = s.size();
      for (int i = 0; i < n; i++) {
        String shape = PosPatternGenerator.shapeNormalize(s.getWord(i));
        s.setShape(i, shape);
      }
    }
    Log.info("[computeShape] done computing word shapes, took "
        + (System.currentTimeMillis() - t)/1000d + " seconds");
  }

  public static void addParses(Iterable<FNParse> ip) {
    long t = System.currentTimeMillis();
    Log.info("[addParses] running Stanford parser on all training/test data...");
    for (FNParse y : ip) {
      Sentence s = y.getSentence();

      // Get and set the Stanford constituency parse
      if (s.getStanfordParse(false) == null) {
        if (PARSER == null) throw new RuntimeException();
        ConstituencyParse cp = PARSER.getCParse(s);
        s.setStanfordParse(cp);
      }

      // Get and set the Stanford basic dependency parse
      if (s.getBasicDeps(false) == null) {
        if (PARSER == null) throw new RuntimeException();
        DependencyParse dp = PARSER.getBasicDParse(s);
        s.setBasicDeps(dp);
      }
    }
    Log.info("[addParses] done parsing, took "
        + (System.currentTimeMillis() - t)/1000d + " seconds");
  }

  public static void stripSyntax(ItemProvider ip) {
    Log.info("[stripSyntax] stripping all syntax out of data!");
    for (FNParse y : ip) {
      Sentence s = y.getSentence();
      s.setBasicDeps(null);
      s.setCollapsedDeps(null);
      s.setStanfordParse(null);
    }
  }

  public static RerankerTrainer configure(ExperimentProperties config) throws Exception {
    File workingDir = config.getOrMakeDir("workingDir");
    boolean useGlobalFeatures = config.getBoolean("useGlobalFeatures", true);
    boolean useEmbeddingParams = config.getBoolean("useEmbeddingParams", false); // else use TemplatedFeatureParams
    boolean useEmbeddingParamsDebug = config.getBoolean("useEmbeddingParamsDebug", false);
    boolean useFeatureHashing = config.getBoolean("useFeatureHashing", true);

    Random rand = new Random(9001);
    RerankerTrainer trainer = new RerankerTrainer(rand, workingDir);
    trainer.reporters = ResultReporter.getReporters(config);

    trainer.trainConf.threads =
      trainer.pretrainConf.threads =
        config.getInt("threads", 1);
    Log.info("[main] using " + trainer.trainConf.threads + " threads");

    // This will evaluate the loss function on the entire dev set every once in a while
    // (you're still guaranteed to make progress on training), but this may take too much
    // time to be acceptable even if it occurs only once.
    trainer.trainConf.allowDynamicStopping =
      trainer.pretrainConf.allowDynamicStopping =
        config.getBoolean("allowDynamicStopping", true);

    trainer.bailOutOfTrainingASAP = config.getBoolean("bailOutOfTrainingASAP", false);

    if (config.containsKey("beamSize")) {
      Log.info("[main] using one train and test beam size (possibly overriding individual settings)");
      trainer.pretrainConf.trainBeamSize =
          trainer.pretrainConf.testBeamSize =
          trainer.trainConf.trainBeamSize =
          trainer.trainConf.testBeamSize =
          config.getInt("beamSize");
    } else {
      trainer.trainConf.trainBeamSize =
          trainer.pretrainConf.trainBeamSize =
          config.getInt("trainBeamSize", 1);
      trainer.trainConf.testBeamSize =
          trainer.pretrainConf.testBeamSize =
          config.getInt("testBeamSize", 1);
    }

    trainer.trainConf.stoppingConditionFrequency
      = trainer.pretrainConf.stoppingConditionFrequency
        = config.getDouble("stoppingConditionFrequency", 6);

    trainer.secsBetweenShowingWeights = config.getDouble("secsBetweenShowingWeights", 5 * 60);

    trainer.useSyntaxSpanPruning = config.getBoolean("useSyntaxSpanPruning", true);

    trainer.trainConf.oracleMode = OracleMode.valueOf(
        config.getString("oracleMode", "RAND_MIN").toUpperCase());

    trainer.pretrainConf.batchSize = config.getInt("pretrainBatchSize", 1);
    trainer.trainConf.batchSize = config.getInt("trainBatchSize", 1);

    if (trainer.trainConf.threads > trainer.trainConf.batchSize) {
      Log.info("[main] trimming train threads to match batch size: "
        + trainer.trainConf.threads + " => " + trainer.trainConf.batchSize);
      trainer.trainConf.threads = trainer.trainConf.batchSize;
    }
    if (trainer.pretrainConf.threads > trainer.pretrainConf.batchSize) {
      Log.info("[main] trimming pretrain threads to match batch size: "
        + trainer.pretrainConf.threads + " => " + trainer.pretrainConf.batchSize);
      trainer.pretrainConf.threads = trainer.pretrainConf.batchSize;
    }

    trainer.performPretrain = config.getBoolean("performPretrain", false);

    trainer.trainConf.batchWithReplacement = config.getBoolean("batchWithReplacement", false);

    // Over-ride the default learning rate (Normal)
    String lrType = config.getString("lrType", "normal");
    if ("normal".equalsIgnoreCase(lrType)) {
      trainer.pretrainConf.learningRate = new LearningRateSchedule.Normal(1);
      trainer.trainConf.learningRate = new LearningRateSchedule.Normal(1);
    } else if ("constant".equalsIgnoreCase(lrType)) {
      trainer.pretrainConf.learningRate = new LearningRateSchedule.Constant(1);
      trainer.trainConf.learningRate = new LearningRateSchedule.Constant(1);
    } else {
      throw new RuntimeException("unknown learning rate type: " + lrType);
    }

    // Set learning rate based on batch size
    int batchSizeThatShouldHaveLearningRateOf1 = config.getInt("lrBatchScale", 16 * 1024);
    trainer.pretrainConf.scaleLearningRateToBatchSize(batchSizeThatShouldHaveLearningRateOf1);
    trainer.trainConf.scaleLearningRateToBatchSize(batchSizeThatShouldHaveLearningRateOf1);

    Log.info("[main] lrType=" + lrType
        + " lrBatchScale=" + batchSizeThatShouldHaveLearningRateOf1
        + " trainLearningRate=" + trainer.trainConf.learningRate);

    // How often to re-estimate the learning rate
    trainer.trainConf.estimateLearningRateFreq = config.getDouble("estimateLearningRateFreq", 7d);
    trainer.pretrainConf.estimateLearningRateFreq = trainer.trainConf.estimateLearningRateFreq;
    if (trainer.trainConf.estimateLearningRateFreq <= 0) {
      Log.info("[main] not re-estimating learning rate: "
          + trainer.trainConf.learningRate);
    }

    // Filter examples based on shards
    // NOTE: This doesn't work with CachedFeatures!
    int numShards = config.getInt("numShards", 0);
    if (numShards > 0) {
      int shard = config.getInt("shard");
      if (shard >= numShards || shard < 0)
        throw new RuntimeException();
      // Only keep examples which have a hashcode matching this shard
      trainer.keep = s -> {
        int h = s.getId().hashCode();
        if (h < 0) h = -h;
        return h % numShards == shard;
      };
    }

    if (config.containsKey("trainTimeLimit")) {
      double mins = config.getDouble("trainTimeLimit");
      Log.info("[main] limiting train to " + mins + " minutes");
      trainer.trainConf.addStoppingCondition(new StoppingCondition.Time(mins));
    }

    final int hashBuckets = config.getInt("numHashBuckets", 2 * 1000 * 1000);
    final double l2Penalty = config.getDouble("l2Penalty", 1e-8);
    Log.info("[main] using l2Penalty=" + l2Penalty);

    // What features to use (if features are being used)
    // OLD: Keep around for legacy support
    String fs = config.getBoolean("simpleFeatures", false)
        ? featureTemplates : featureTemplatesSearch;
    String otherFs = config.getString("featureSetFile", "");
    if (!otherFs.isEmpty())
      fs = getFeatureSetFromFileNewNew(otherFs);
    Log.info("using featureSet=" + fs);

    // Enable the CachedFeatures module
    if (config.getBoolean("useCachedFeatures", false)) {
      Log.info("[main] using cached features!");

      // stringTemplate <-> intTemplate and other stuff like template cardinality
      BiAlph bialph = new BiAlph(
          config.getExistingFile("cachedFeatures.bialph"),
          LineMode.valueOf(config.getString("cachedFeatures.bialph.lineMode")));

      // Convert string feature set to ints for CachedFeatures (using BiAlph)
      List<int[]> features = new ArrayList<>();
      for (String featureString : TemplatedFeatures.tokenizeTemplates(fs)) {
        List<String> strTemplates = TemplatedFeatures.tokenizeProducts(featureString);
        int n = strTemplates.size();
        int[] intTemplates = new int[n];
        for (int i = 0; i < n; i++) {
          Log.info("looking up template: " + strTemplates.get(i));
          int t = bialph.mapTemplate(strTemplates.get(i));
          assert t >= 0;
          intTemplates[i] = t;
        }
        features.add(intTemplates);
      }

      // Instantiate the module (holder of the data)
      trainer.cachedFeatures = new CachedFeatures(bialph, features);

      // Load the sentId -> FNParse mapping (cached features only gives you sentId and features)
      trainer.cachedFeatures.sentIdsAndFNParses = new PropbankFNParses(config);
      Log.info("[main] train.size=" + trainer.cachedFeatures.sentIdsAndFNParses.trainSize()
          + " dev.size=" + trainer.cachedFeatures.sentIdsAndFNParses.devSize()
          + " test.size=" + trainer.cachedFeatures.sentIdsAndFNParses.testSize());

      // Start loading the data in the background
      int numDataLoadThreads = config.getInt("cachedFeatures.numDataLoadThreads", 1);
      Log.info("[main] loading data in the background with "
          + numDataLoadThreads + " extra threads");
      List<File> featureFiles = FileUtil.find(
          config.getExistingDir("cachedFeatures.featuresParent"),
          config.getString("cachedFeatures.featuresGlob", "glob:**/*"));
      assert numDataLoadThreads > 0;
      boolean readForever = false;  // only useful with reservoir sampling, not doing that
      boolean skipEntriesNotInSentId2ParseMap = false;
      for (int i = 0; i < numDataLoadThreads; i++) {
        List<File> rel = ShardUtils.shard(featureFiles, f -> f.getPath().hashCode(), i, numDataLoadThreads);
        Thread t = new Thread(trainer.cachedFeatures.new Inserter(rel, readForever, skipEntriesNotInSentId2ParseMap));
        t.start();
      }

      // Setup the params
      int dimension = config.getInt("cachedFeatures.hashingTrickDim", 1 * 1024 * 1024);
      int numRoles = config.getInt("cachedFeatures.numRoles",
          trainer.cachedFeatures.sentIdsAndFNParses.getMaxRole());
      CachedFeatures.Params params = trainer.cachedFeatures.new Params(dimension, numRoles, rand);
      trainer.cachedFeatures.params = params;
      trainer.statelessParams = params;

      // Make sure that DeterministicRolePruning knows about CachedFeatures
      trainer.pretrainConf.argPruningMode = Mode.CACHED_FEATURES;
      trainer.trainConf.argPruningMode = Mode.CACHED_FEATURES;

    } else if (useEmbeddingParams) {
      // This is the path that will be executed when not debugging
      Log.info("[main] using embedding params");
      int embeddingSize = 2;
      EmbeddingParams ep = new EmbeddingParams(embeddingSize, l2Penalty, trainer.rand);
      ep.learnTheta(true);
      if (useEmbeddingParamsDebug)
        ep.debug(new TemplatedFeatureParams("embD", featureTemplates, hashBuckets), l2Penalty);
      trainer.statelessParams = ep;
    } else {
      Log.info("[main] using features=" + fs);
      if (useFeatureHashing) {
        Log.info("[main] using TemplatedFeatureParams with feature hashing");
        trainer.statelessParams =
            new TemplatedFeatureParams("statelessA", fs, l2Penalty, hashBuckets);
      } else {
        Log.info("[main] using TemplatedFeatureParams with an Alphabet");
        trainer.statelessParams =
            new TemplatedFeatureParams("statelessH", fs, l2Penalty);
      }
    }


    // There may be some fixed stateless params which should be added
    String fixedParmasSer = "fixedStatelessParamsJserFile";
    if (config.containsKey(fixedParmasSer)) {
      File fixedSer = config.getExistingFile(fixedParmasSer);
      Log.info("adding fixed stateless params: " + fixedSer.getPath());
      Params.Stateless fixed = (Params.Stateless) FileUtil.deserialize(fixedSer); 
      trainer.addStatelessParams(new Fixed.Stateless(fixed));
    }


    if (config.getBoolean("featCoversFrames", true))
      trainer.addStatelessParams(new GlobalFeature.CoversFrames(l2Penalty));

    // Setup tau/pruning parameters
    if (config.getBoolean("useDynamicTau", true)) {
      // Old way: very simple features
      //      double tauL2Penalty = config.getDouble("tauL2Penalty", 2e-2);
      //      double tauLearningRate = config.getDouble("taulLearningRate", Math.sqrt(trainer.trainConf.batchSize) / 10d);
      //      trainer.tauParams = new Params.PruneThreshold.Impl(tauL2Penalty, tauLearningRate);
      // Older way: very rich features.
      if (trainer.cachedFeatures != null) {
        Log.info("[main] using CachedFeatures for tau");
        trainer.tauParams = (CachedFeatures.Params) trainer.statelessParams;
      } else if (useFeatureHashing) {
        Log.info("[main] using TemplatedFeatureParams with feature hashing for tau");
        trainer.tauParams =
            new TemplatedFeatureParams("tauA", fs, l2Penalty, hashBuckets);
      } else {
        Log.info("[main] using TemplatedFeatureParams with an Alphabet for tau");
        trainer.tauParams =
            new TemplatedFeatureParams("tauH", fs, l2Penalty);
      }
    } else {
      Log.warn("[main] you probably don't want to use constant params for tau!");
      trainer.tauParams = Params.PruneThreshold.Const.ZERO;
    }

    if (useGlobalFeatures) {
      double globalL2Penalty = config.getDouble("globalL2Penalty", 1e-7);
      Log.info("[main] using global features with l2p=" + globalL2Penalty);

      // helps
      if (config.getBoolean("globalFeatArgLoc", false))
        trainer.addGlobalParams(new GlobalFeature.ArgLoc(globalL2Penalty));

      // slow, but better than non-simple version
      if (config.getBoolean("globalFeatArgLocSimple", false))
        trainer.addGlobalParams(new GlobalFeature.ArgLocSimple(globalL2Penalty));

      // helps
      if (config.getBoolean("globalFeatNumArgs", false))
        trainer.addGlobalParams(new GlobalFeature.NumArgs(globalL2Penalty));

      // helps
      if (config.getBoolean("globalFeatRoleCooc", false))
        trainer.addGlobalParams(new GlobalFeature.RoleCooccurenceFeatureStateful(globalL2Penalty));

      // worse than non-simple version
      if (config.getBoolean("globalFeatRoleCoocSimple", false))
        trainer.addGlobalParams(new GlobalFeature.RoleCoocSimple(globalL2Penalty));

      // helps
      if (config.getBoolean("globalFeatArgOverlap", false))
        trainer.addGlobalParams(new GlobalFeature.ArgOverlapFeature(globalL2Penalty));

      // helps
      if (config.getBoolean("globalFeatSpanBoundary", false))
        trainer.addGlobalParams(new GlobalFeature.SpanBoundaryFeature(globalL2Penalty));
    }

    final boolean forceLeftRightInference = config.getBoolean("forceLeftRightInference", false);
    Log.info("[main] forceLeftRightInference=" + forceLeftRightInference);
    if (forceLeftRightInference) {
      ActionType.COMMIT.forceLeftRightInference();
      ActionType.PRUNE.forceLeftRightInference();
    }

    final boolean perceptron = config.getBoolean("perceptron", false);
    Log.info("[main] perceptron=" + perceptron);
    if (perceptron)
      Reranker.PERCEPTRON = true;

    return trainer;
  }

  public static void setFeatureMode(ExperimentProperties config) {
    String fm = config.getString("featureMode", "");
    if (fm.isEmpty()) {
      Log.info("[main] no feature mode specified!");
    } else {
      Log.info("[main] featureMode=" + fm + "\tNOTE: This can overwrite other settings!");
      // This info can also be found in scripts/tge_global_train.py
      switch (fm.toUpperCase()) {
      case "FULL":
        config.setProperty("useGlobalFeatures", "True");
        config.setProperty("globalFeatNumArgs", "True");
        config.setProperty("globalFeatArgLoc", "False");
        config.setProperty("globalFeatArgLocSimple", "True");
        config.setProperty("globalFeatArgOverlap", "False");
        config.setProperty("globalFeatSpanBoundary", "False");
        config.setProperty("globalFeatRoleCooc", "True");
        config.setProperty("globalFeatRoleCoocSimple", "False");
        break;
      case "ARG-LOCATION":
      case "ARG-LOC":
        config.setProperty("globalFeatArgLocSimple", "True");
        config.setProperty("globalFeatNumArgs", "False");
        config.setProperty("globalFeatRoleCoocSimple", "False");
        break;
      case "NUM-ARGS":
      case "NUM-ARG":
        config.setProperty("globalFeatArgLocSimple", "False");
        config.setProperty("globalFeatNumArgs", "True");
        config.setProperty("globalFeatRoleCoocSimple", "False");
        break;
      case "ROLE-COOC":
        config.setProperty("globalFeatArgLocSimple", "False");
        config.setProperty("globalFeatNumArgs", "False");
        config.setProperty("globalFeatRoleCoocSimple", "True");
        break;
      case "LOCAL":
        config.setProperty("useGlobalFeatures", "False");
        break;
      default:
        throw new RuntimeException("unknown feature mode: " + fm);
      }
    }
  }

  /**
   * First arg must be the job name (for tie-ins with tge) and the remaining are
   * key-value pairs.
   */
  public static void main(String[] args) throws Exception {
    System.out.println(Arrays.toString(args));
    assert args.length % 2 == 1;

    String jobName = args[0];
    Log.info("[main] starting, jobName=" + jobName);
//    ExperimentProperties config = new ExperimentProperties();
//    config.putAll(Arrays.copyOfRange(args, 1, args.length), false);
    ExperimentProperties config = ExperimentProperties.init(Arrays.copyOfRange(args, 1, args.length));

    Reranker.GRADIENT_BUGFIX = config.getBoolean("gradientBugfix", true);

    // First determine the feature mode, and add implied flags
    setFeatureMode(config);

    File workingDir = config.getOrMakeDir("workingDir");
    boolean testOnTrain = config.getBoolean("testOnTrain", false);

    Reranker.COST_FN = config.getDouble("costFN", 1);
    Log.info("[main] costFN=" + Reranker.COST_FN + " costFP=1");

    RerankerTrainer trainer = configure(config);

    // If you are loading a model file => skip training data
    final String modelFileKey = "loadModelFromFile";    // for serialization of Reranker
    final String paramsFileKey = "loadParamsFromFile";  // for serialization of Params.NetworkAvg
    boolean canSkipTrainData = false;
    canSkipTrainData |= config.containsKey(modelFileKey);
    canSkipTrainData |= config.containsKey(paramsFileKey);
    Log.info("[main] canSkipTrainData=" + canSkipTrainData);

    // Get train and test data.
    final boolean isParamServer = config.getBoolean("isParamServer", false);
    final boolean realTest = config.getBoolean("realTestSet", false);
    final boolean propbank = config.getBoolean("propbank", false);
    ItemProvider train = null, dev = null, test;
    if (isParamServer) {
      train = null;
      dev = null;
      test = null;
    } else if (trainer.bailOutOfTrainingASAP) {
      train = new ItemProvider.ParseWrapper(Collections.emptyList());
      dev = new ItemProvider.ParseWrapper(Collections.emptyList());
      test = new ItemProvider.ParseWrapper(Collections.emptyList());
    } else {
      if (realTest)
        Log.info("[main] running on real test set");
      else
        Log.info("[main] running on dev set");

      // Load FrameNet/Propbank
      if (propbank)
        FrameIndex.getPropbank();
      else
        FrameIndex.getFrameNet();

      if (trainer.cachedFeatures != null) {
        Log.info("[main] using CachedFeatures to serve up FNParses");
        PropbankFNParses p = trainer.cachedFeatures.sentIdsAndFNParses;
        train = trainer.cachedFeatures.new ItemProvider(p.trainSize(), false, false);
        dev = trainer.cachedFeatures.new ItemProvider(p.devSize(), true, false);
        test = trainer.cachedFeatures.new ItemProvider(p.testSize(), false, true);
      } else {
        if (propbank) {
          Log.info("[main] running on propbank data");
          boolean noParses = config.getBoolean("rerankerTrainer.noParsesForPropbank", false);
          ParsePropbankData.Redis propbankAutoParses = noParses ? null : new ParsePropbankData.Redis(config);
          PropbankReader pbr = new PropbankReader(config, propbankAutoParses);
          pbr.setKeep(trainer.keep);
          if (realTest) {
            Log.info("[main] reading real propbank data...");
            if (canSkipTrainData) {
              train = new ItemProvider.ParseWrapper(Collections.emptyList());
              dev = new ItemProvider.ParseWrapper(Collections.emptyList());
            } else {
              train = pbr.getTrainData();
              dev = pbr.getDevData();
            }
            test = pbr.getTestData();
          } else {
            Log.info("[main] reading dev propbank data...");
            if (canSkipTrainData) {
              train = new ItemProvider.ParseWrapper(Collections.emptyList());
              dev = new ItemProvider.ParseWrapper(Collections.emptyList());
            } else {
              train = pbr.getTrainData();
              dev = pbr.getDevData();
            }
            test = pbr.getDevData();
          }
        } else if (realTest) {
          Log.info("[main] running on framenet data");
          if (canSkipTrainData) {
            train = new ItemProvider.ParseWrapper(Collections.emptyList());
            dev = new ItemProvider.ParseWrapper(Collections.emptyList());
          } else {
            ItemProvider trainAndDev = new ItemProvider.ParseWrapper(DataUtil.iter2list(
                FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences()));
            double propDev = 0.2;
            int maxDev = 1000;
            ItemProvider.TrainTestSplit foo =
                new ItemProvider.TrainTestSplit(trainAndDev, propDev, maxDev, trainer.rand);
            train = foo.getTrain();
            dev = foo.getTest();
          }
          test = new ItemProvider.ParseWrapper(DataUtil.iter2list(
              FileFrameInstanceProvider.dipanjantestFIP.getParsedSentences()));
        } else {
          Log.info("[main] running on framenet data");
          ItemProvider trainAndTest = new ItemProvider.ParseWrapper(DataUtil.iter2list(
              FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences()));
          if (testOnTrain) {
            double propDev = 0.2;
            int maxDev = 1000;
            ItemProvider.TrainTestSplit foo =
                new ItemProvider.TrainTestSplit(trainAndTest, propDev, maxDev, trainer.rand);
            train = foo.getTrain();
            dev = foo.getTest();
            test = trainAndTest;
            trainer.pretrainConf.tuneOnTrainingData = true;
            trainer.pretrainConf.maxDev = 25;
            trainer.trainConf.tuneOnTrainingData = true;
            trainer.trainConf.maxDev = 25;
          } else {
            double propTest = 0.25;
            int maxTest = 99999;
            ItemProvider.TrainTestSplit trainTest =
                new ItemProvider.TrainTestSplit(trainAndTest, propTest, maxTest, trainer.rand);
            train = trainTest.getTrain();
            test = trainTest.getTest();
          }
        }
      }
      if (train != null)
        assert dev != null;

      // Only take a sub-set of the data based on sharding.
      if (trainer.keep != null && trainer.keep != KEEP_ALL) {
        Log.info("[main] filtering remaining examples by trainer.keep predicate");
        Log.info("[main] before: train.size=" + train.size() + " test.size=" + test.size());
        train = ItemProvider.Slice.shard(train, trainer.keep);
        test = ItemProvider.Slice.shard(test, trainer.keep);
        Log.info("[main] after: train.size=" + train.size() + " test.size=" + test.size());
      }

      // Limit according to nTrain
      // TODO Move nTrain before shards by letting PropbankData know about nTrain
      final int nTrain = config.getInt("nTrain", 999999);
      final int nTest = config.getInt("nTest", 999999);
      train = new ItemProvider.Slice(train, nTrain, trainer.rand);
      test = new ItemProvider.Slice(test, nTest, trainer.rand);
    }

    // Setup parameter averaging over the network
    int numClientsForParamAvg = config.getInt("numClientsForParamAvg", 0);
    trainer.parameterServerHost = config.getString("paramServerHost", "none");
    if (isParamServer || numClientsForParamAvg > 0) {

      // Setup the network parameters (do this as late as possible)
      int port = config.getInt("paramServerPort", 7777);
      boolean checkAlphabetEquality = true;
      trainer.networkParams = new Params.NetworkAvg(new Params.Glue(
              trainer.statefulParams,
              trainer.statelessParams,
              trainer.tauParams),
              checkAlphabetEquality);
      trainer.networkParams.debug = trainer.trainConf.debugNetworkParamAveraging
          || trainer.pretrainConf.debugNetworkParamAveraging;

      int secondsBetweenSaves = config.getInt("paramAvgSecBetweenSaves", 5 * 60);
      String hostName = InetAddress.getLocalHost().getHostName();
      Log.info("[main] numClientsForParamAvg=" + numClientsForParamAvg
          + " paramServerHost=" + trainer.parameterServerHost
          + " hostName=" + hostName
          + " port=" + port
          + " secondsBetweenSaves=" + secondsBetweenSaves);

      // Only feature hashing is allowed: otherwise we're not guaranteed to have
      // the same feature indices.
      Log.info("[main] disabling growing in AveragedWeights, should be using feature "
          + "hashing because of network parameter hashing, and shouldn't need to grow");
      AveragedWeights.GROWING_ALLOWED = false;

      if (isParamServer) {
        // Server
        Log.info("[main] server: networkParams=" + trainer.networkParams);
        trainer.networkParams.setViaScaleAndAdd = false;
        trainer.parameterServer = new NetworkParameterAveraging.Server(trainer.networkParams, port);

        File checkpointDir = new File(workingDir, "paramAverages");
        if (!checkpointDir.isDirectory()) checkpointDir.mkdir();
        trainer.parameterServer.saveModels(checkpointDir, secondsBetweenSaves);

        Log.info("[main] starting parameter server: " + trainer.parameterServer);
        trainer.parameterServer.debug = true;
        trainer.parameterServer.run();
        Log.info("[main] server is done running for whatever reason, exiting");
        System.exit(0);
      } else {
        // Client
        assert numClientsForParamAvg > 0;
        Log.info("[main] client: networkParams=" + trainer.networkParams);
        trainer.networkParams.setViaScaleAndAdd = true;
        trainer.parameterServerClient = new NetworkParameterAveraging.Client(
            trainer.networkParams,
            trainer.parameterServerHost,
            port);
        trainer.parameterServerClient.secondsBetweenContactingServer =
            secondsBetweenSaves;
        if (config.getBoolean("parallelLearnDebug", false))
          trainer.parameterServerClient.debug = true;
      }
    } else {
      Log.info("[main] not setting up network parameter averaging");
      trainer.parameterServerHost = null;
    }

    // Log the train and test set
    logTrainTestDatapoints(new File(workingDir, "train-test-split.txt"), train, test);

    Log.info("[main] nTrain=" + train.size() + " nTest=" + test.size() + " testOnTrain=" + testOnTrain + " nDev=" + dev.size());
    Log.info("[main] " + Describe.memoryUsage());

    // Set the word shapes: features will try to do this otherwise, best to do ahead of time.
    if (config.getBoolean("precomputeShape", true)) {
      Log.info("[main] precomputing word shapes");
      computeShape(train);
      computeShape(test);
    }

    // Data does not come with Stanford parses straight off of disk, add them
    if (config.getBoolean("addStanfordParses", true)) {
      Log.info("[main] addStanfordParses: parsing the train+test data");
      if (propbank)
        Log.warn("you probably don't want to parse propbank, see ParsePropbankData");
      PARSER = ConcreteStanfordWrapper.getSingleton(true);
      addParses(train);
      addParses(test);
      Log.info("[main] addStanfordParses before GC: " + Describe.memoryUsage());
      PARSER = null;
      ConcreteStanfordWrapper.dumpSingletons();
      System.gc();
      Log.info("[main] addStanfordParses after GC:  " + Describe.memoryUsage());
    }

    if (config.getBoolean("noSyntax", false)) {
      Log.info("[main] stripping syntax from train and test");
      stripSyntax(train);
      stripSyntax(test);
    }

    final boolean dropout = config.getBoolean("dropout", false);
    Reranker model = null;
    if (config.containsKey(modelFileKey)) {
      // Load a model from file
      File modelFile = config.getExistingFile(modelFileKey);
      Log.info("[main] loading model from " + modelFile.getPath());
//      model = trainer.instantiate();
//      model.deserializeParams(modelFile);
      ObjectInputStream ois = new ObjectInputStream(new FileInputStream(modelFile));
      model = (Reranker) ois.readObject();
      ois.close();
    } else if (config.containsKey(paramsFileKey)) {
      // Load just the params (instead of the entire Reranker).
      // This is useful for distributed training, which only uses the Params.
      File paramsFile = config.getExistingFile(paramsFileKey);
      Log.info("[main] loading params from " + paramsFile.getPath());
      try {
        Object obj = FileUtil.deserialize(paramsFile);
        Log.info("[main] deser1 obj=" + obj);
        Params.NetworkAvg p = (Params.NetworkAvg) obj;
        Log.info("[main] deser1 p=" + p);
        Params.Glue glue = (Params.Glue) p.getAverage();
        Log.info("[main] deser1 glue=" + glue);
        model = new Reranker(
            glue.getStateful(),
            glue.getStateless(),
            glue.getTau(),
            trainer.trainConf.argPruningMode,
            trainer.cachedFeatures,
            trainer.trainConf.trainBeamSize,
            trainer.trainConf.testBeamSize,
            trainer.rand);
      } catch (Exception e) {
        e.printStackTrace();
      }
      Log.info("just constructed model from saved params (skipping train): " + model);
    } else {
      // Train
      if (dropout) {
        Log.info("[main] turning on dropout");
        trainer.cachedFeatures.params.setDropoutProbability(0.5);
        trainer.cachedFeatures.params.setDropoutMode(DropoutMode.TRAIN);
      }
      config.store(System.out, null);   // show the config for posterity
      Log.info("[main] " + Describe.memoryUsage());
      Log.info("[main] starting training, config:");
      assert train != null;
      assert dev != null;
      model = trainer.train1(train, dev);

//      // Save the model (using DataOutputStream)
//      File modelFile = new File(workingDir, "model.bin_deprecated");
//      Log.info("saving model to " + modelFile.getPath());
//      model.serializeParams(modelFile);
    }


    // Release some memory
    Log.info("[main] before GC: " + Describe.memoryUsage());
    ConcreteStanfordWrapper.dumpSingletons();
    train = null;
    System.gc();
    Log.info("[main] after GC:  " + Describe.memoryUsage());


    // Evaluate on test
    Log.info("[main] done training, evaluating");
    if (dropout) {
      Log.info("[main] turning off dropout");
      model.cachedFeatures.params.setDropoutMode(DropoutMode.TEST);
    }
    File diffArgsFileTest = new File(workingDir, "diffArgs.test.txt");
    File predictionsFileTest = new File(workingDir, "predictions.test.txt");
    File diffArgsFileDev = new File(workingDir, "diffArgs.dev.txt");
    File predictionsFileDev = new File(workingDir, "predictions.dev.txt");
    File semDirTest = null, semDirDev = null;
    if (!propbank) {
      Log.info("[main] performing Semafor eval beceause we are working on FN");
      semDirTest = new File(workingDir, "semaforEvalTest");
      semDirDev = new File(workingDir, "semaforEvalDev");
      if (!semDirTest.isDirectory()) semDirTest.mkdir();
      if (!semDirDev.isDirectory()) semDirDev.mkdir();
    } else {
      Log.info("[main] skipping Semafor eval because we are working on propbank");
    }
    Log.info("[main] evaluating on test");
    Map<String, String> results = new HashMap<>();
    Map<String, Double> perfResultsTest = new HashMap<>();
    try {
      perfResultsTest = eval(model, trainer.trainConf, test, semDirTest, "[main] test", diffArgsFileTest, predictionsFileTest);
      results.putAll(ResultReporter.mapToString(perfResultsTest));
      results.putAll(ResultReporter.mapToString(config));

      // Evaluate on dev
      Log.info("[main] evaluating on dev");
      eval(model, trainer.trainConf, dev, semDirDev, "[main] dev", diffArgsFileDev, predictionsFileDev);
    } catch (Exception e) {
      Log.warn("some type of evaluation failed!");
      e.printStackTrace();
    }

    // Serialize the model
    File jserFile = new File(workingDir, "model.jser");
    Log.info("[main] serializing model to " + jserFile.getPath());
    try {
      ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(jserFile));
      oos.writeObject(model);
      oos.close();
      Log.info("[main] done serializing.");
    } catch (Exception e) {
      Log.warn("[main] problem serializing model!");
      e.printStackTrace();
    }

    // Serialize just the stateless params.
    // During feature selection, this can be absorbed into Fixed params and
    // a single new feature can be tried out.
    try {
      File statelessParamsFile = new File(workingDir, "statelessParams.jser.gz");
      Log.info("[main] saving stateless params to " + statelessParamsFile.getPath());
      FileUtil.serialize(trainer.statelessParams, statelessParamsFile);
    } catch (Exception e) {
      Log.warn("[main] problem serializing stateless params!");
      e.printStackTrace();
    }

    // Save the configuration
    try {
      OutputStream os = new FileOutputStream(new File(workingDir, "config.xml"));
      config.storeToXML(os, "ran on " + new Date());
      os.close();
    } catch (Exception e) {
      Log.warn("[main] problem serializing configuration to XML");
      e.printStackTrace();
    }

    // Report results back to tge
    String k = BasicEvaluation.argOnlyMicroF1.getName();
    double mainResult = perfResultsTest.getOrDefault(k, -1.0);
    for (ResultReporter rr : trainer.reporters) {
      try {
        rr.reportResult(mainResult, jobName, ResultReporter.mapToString(results));
      } catch (Exception e) {
        Log.warn("problem reporting result " + mainResult + " to " + rr);
        e.printStackTrace();
      }
    }

    Log.info("[main] totally done!");
  }

  private static final String featureTemplatesSearch =
      getFeatureSetFromFile("featureSet.full.txt");
  private static final String featureTemplates =
      "1"
      + " + frameRole * 1"
      + " + frameRoleArg * 1"
      + " + role * 1"
      + " + roleArg * 1"

      + " + frameRole * span1FirstWord"
      + " + frameRole * span1FirstShape"
      + " + frameRole * span1FirstPos"

      + " + frameRole * span1LastWord"
      + " + frameRole * span1LastShape"
      + " + frameRole * span1LastPos"

      + " + frameRole * span1LeftShape"
      + " + frameRole * span1LeftPos"

      + " + frameRole * span1RightShape"
      + " + frameRole * span1RightPos"

      + " + frameRole * head1Lemma"
      + " + frameRole * head1ParentLemma"
      + " + frameRole * head1WordWnSynset"
      + " + frameRole * head1Shape"
      + " + frameRole * head1Pos"

      + " + frameRole * span1PosPat-COARSE_POS-1-1"
      + " + frameRole * span1PosPat-WORD_SHAPE-1-1"

      + " + frameRole * span1StanfordCategory"
      + " + frameRole * span1StanfordRule"

      + " + frameRole * head1CollapsedParentDir"
      + " + frameRole * head1CollapsedLabel"

      + " + frameRole * head1head2Path-POS-DEP-t"
      + " + role * head1head2Path-POS-DEP-t"

      /* These seem to hurt performance ???
      + " + roleArg * span1PosPat-COARSE_POS-1-1"
      + " + roleArg * span1PosPat-WORD_SHAPE-1-1"
      + " + arg * span1PosPat-COARSE_POS-1-1"
      + " + arg * span1PosPat-WORD_SHAPE-1-1"
      */
      + " + frameRole * span1span2Overlap"
      + " + frameRole * Dist(SemaforPathLengths,Head1,Head2)";

  /**
   * Reads the tab-separated format:
   * score, ig, hx, arity, intTemplates, stringTemplates
   */
  private static String getFeatureSetFromFileNewNew(String path) {
    Log.info("[main] reading from " + path);
    File f = new File(path);
    if (!f.isFile())
      throw new IllegalArgumentException("not a file: " + path);
    List<String> features = new ArrayList<>();
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] toks = line.split("\t");
        if (toks.length != 6)
          Log.warn("uknown line format: " + line);
        features.add(toks[5]);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return StringUtils.join(" + ", features);
  }

  private static String getFeatureSetFromFileNew(String path) {
    Log.info("[main] getFeatureSetFromFileNew path=" + path);
    File f = new File(path);
    if (!f.isFile())
      throw new RuntimeException("not a file: " + path);
    StringBuilder sb = new StringBuilder();
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        if (line.charAt(0) == '#')
          continue;
        sb.append(" " + line);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return sb.toString().trim();
  }

  public static String getFeatureSetFromFile(String path) {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    try (InputStream is = classLoader.getResourceAsStream(path)) {
      assert is != null : "couldn't find: " + path;
      BufferedReader br = new BufferedReader(new InputStreamReader(is));
      String line = br.readLine();
      String[] toks = line.split("\t", 2);
      String features = toks[1];
      // Don't need the stage prefixes that we had before.
      features = features.replaceAll("RoleSpan(Labeling|Pruning)Stage-(regular|latent|none)\\*", "");
      return features;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void logTrainTestDatapoints(File dest, ItemProvider train, ItemProvider test) {
    try (BufferedWriter w = FileUtil.getWriter(dest)) {
      int nTrain = train.size();
      for (int i = 0; i < nTrain; i++)
        w.write("train\t" + train.label(i).getSentence().getId() + "\n");
      int nTest = test.size();
      for (int i = 0; i < nTest; i++)
        w.write("test\t" + test.label(i).getSentence().getId() + "\n");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
