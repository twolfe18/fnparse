package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.experiment.grid.ResultReporter;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.params.CheatingParams;
import edu.jhu.hlt.fnparse.rl.params.DecoderBias;
import edu.jhu.hlt.fnparse.rl.params.EmbeddingParams;
import edu.jhu.hlt.fnparse.rl.params.GlobalFeature;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateful;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateless;
import edu.jhu.hlt.fnparse.rl.params.TemplatedFeatureParams;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.fnparse.util.ExperimentProperties;
import edu.jhu.hlt.fnparse.util.LearningRateEstimator;
import edu.jhu.hlt.fnparse.util.LearningRateSchedule;
import edu.jhu.hlt.fnparse.util.MultiTimer;
import edu.jhu.hlt.fnparse.util.PosPatternGenerator;
import edu.jhu.hlt.fnparse.util.ThresholdFinder;
import edu.jhu.hlt.fnparse.util.TimeMarker;
import edu.jhu.hlt.fnparse.util.Timer;
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

  public static enum OracleMode {
    MAX,
    MIN,
    RAND,
  }

  // may differ across pretrain/train
  public class Config {
    // Meta
    public final String name;

    // General parameters
    public int threads = 1;
    public int beamSize = 1;
    public int batchSize = 4;	 // If 0, compute an exact gradient

    // Stopping condition
    public StoppingCondition stopping = new StoppingCondition.Time(6 * 60);
    public double stoppingConditionFrequency = 5;   // Higher means check the stopping condition less frequently, time multiple of hammingTrain

    // If true (and dev settings permit), train2 will automatically add a
    // StoppingCondition.DevSet to the list of stopping conditions.
    public boolean allowDynamicStopping = true;

    // Normally if a model has no Params.Stateful features, then getStatelessUpdate
    // is used to train the model. If this is true, getFullUpdate will always
    // be used, regardless of params.
    public boolean forceGlobalTrain = true;

    // How to implement bFunc for oracle
    public OracleMode oracleMode = OracleMode.RAND;

    // Learning rate estimation parameters
    public LearningRateSchedule learningRate = new LearningRateSchedule.Normal(1);
    public double estimateLearningRateFreq = 4;       // Higher means estimate less frequently, time multiple of hammingTrain
    public int estimateLearningRateGranularity = 3;   // Must be odd and >2, how many LR's to try
    public double estimateLearningRateSpread = 5;     // Higher means more spread out
    public int estimateLearningRateSteps = 8;         // How many batche steps to take when evaluating a lr
    public int estimateLearningRateDevLimit = 40;     // Size of dev set for evaluating improvement, also limited by the amount of dev data

    // F1-Tuning parameters
    private double propDev = 0.2d;
    private int maxDev = 50;
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
        LOG.warn("[scaleLearningRateToBatchSize] batchSize=0 (exact gradient), "
            + "so leaving learning rate as-is: " + learningRate);
        return;
      }
      double f = Math.sqrt(batchSize) / Math.sqrt(batchSizeWithLearningRateOf1);
      LOG.info("[scaleLearningRateToBatchSize] scaling learning rate of name="
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
      stopping = new StoppingCondition.Conjunction(stopping, s);
      return s;
    }

    public String toString() {
      return "(Config " + name
          + " threads=" + threads
          + " beam=" + beamSize
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
  public boolean performPretrain;

  // Model parameters
  public Params.Stateful statefulParams = Stateful.NONE;
  public Params.Stateless statelessParams = Stateless.NONE;
  public Params.PruneThreshold tauParams = Params.PruneThreshold.Const.ONE;

  public RerankerTrainer(Random rand, File workingDir) {
    if (!workingDir.isDirectory())
      throw new IllegalArgumentException();
    this.rand = rand;
    File pwd = new File(workingDir, "wd-pretrain");
    if (!pwd.isDirectory())
      pwd.mkdir();
    this.pretrainConf = new Config("pretrain", pwd, rand);
    this.pretrainConf.beamSize = 1;
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

  /** If you don't want anything to print, just provide showStr=null */
  public static Map<String, Double> eval(Reranker m, ItemProvider ip, String showStr) {
    List<FNParse> y = ItemProvider.allLabels(ip);
    List<FNParse> yHat = m.predict(y);
    Map<String, Double> results = BasicEvaluation.evaluate(y, yHat);
    if (showStr != null)
      BasicEvaluation.showResults(showStr, results);
    return results;
  }

  public static double eval(Reranker m, ItemProvider ip, StdEvalFunc objective) {
    String showStr = null;
    Map<String, Double> results = eval(m, ip, showStr);
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
        LOG.info(String.format("[tuneModelForF1] trying out recallBias=%+5.2f", threshold));
        bias.setRecallBias(threshold);
        Map<String, Double> results = eval(model, dev, SHOW_FULL_EVAL_IN_TUNE
            ? String.format("[tune recallBias=%.2f]", bias.getRecallBias()) : null);
        double perf = results.get(conf.objective.getName());
        LOG.info(String.format("[tuneModelForF1] recallBias=%+5.2f perf=%.3f",
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
    LOG.info("[tuneModelForF1] chose recallBias=" + best.get1()
        + " with " + conf.objective.getName() + "=" + best.get2());
    bias.setRecallBias(best.get1());
    return best.get2();
  }

  /** Trains and tunes a full model */
  public Reranker train1(ItemProvider ip) {
    if (statefulParams == Stateful.NONE && statelessParams == Stateless.NONE)
      throw new IllegalStateException("you need to set the params");

    Reranker m = new Reranker(
        Params.Stateful.NONE,
        statelessParams,
        tauParams,
        pretrainConf.beamSize,
        rand);
    if (performPretrain) {
      LOG.info("[train1] local train");
      train2(m, ip, pretrainConf);
    } else {
      LOG.info("[train1] skipping pretrain");
    }

    LOG.info("[train1] global train");
//    if (statefulParams != Params.Stateful.NONE) {
      m.setStatefulParams(statefulParams);
      m.setBeamWidth(trainConf.beamSize);
      train2(m, ip, trainConf);
//    } else {
//      LOG.info("[train1] skipping global train because there are no stateful params");
//    }

    LOG.info("[train1] done, times:\n" + timer);
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
    LOG.info("[estimateLearningRate] starting at lr=" + lrOldV);

    // Create the directory that model params will be saved into
    final File paramDir = new File(conf.workingDir, "lrEstParams");
    if (!paramDir.isDirectory())
      paramDir.mkdir();
    assert paramDir.isDirectory();

    // Only use some of the dev data for estimating the learning rate
    final ItemProvider devSmall;
    if (dev.size() > conf.estimateLearningRateDevLimit) {
      LOG.info("[estimateLearningRate] chopping down dev down: "
          + dev.size() + " => " + conf.estimateLearningRateDevLimit);
      devSmall = new ItemProvider.Slice(dev, conf.estimateLearningRateDevLimit, conf.rand);
    } else {
      devSmall = dev;
    }

    // Make an adapter for LearningRateEstimator
    LearningRateEstimator.Model model = new LearningRateEstimator.Model() {
      private DoubleSupplier devLoss = modelLossOnData(m, devSmall, conf);
      @Override
      public void train() {
        try {
          for (int i = 0; i < conf.estimateLearningRateSteps; i++)
            hammingTrainBatch(m, null, train, conf, -1, "lrEst");
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
  }

  private static DoubleSupplier modelLossOnData(Reranker m, ItemProvider dev, Config conf) {
    return new DoubleSupplier() {
      @Override
      public double getAsDouble() {
        LOG.info("[devLossFunc] computing dev set loss on " + dev.size() + " examples");
        double loss = 0d;
        for (int i = 0; i < dev.size(); i++) {
          FNParse y = dev.label(i);
          List<Item> rerank = dev.items(i);
          State init = State.initialState(y, rerank);
          Update u = m.hasStatefulFeatures() || conf.forceGlobalTrain
              ? m.getFullUpdate(init, y, conf.oracleMode, conf.rand, null, null)
              : m.getStatelessUpdate(init, y);
          loss += u.violation();
          assert Double.isFinite(loss) && !Double.isNaN(loss);
        }
        loss /= dev.size();
        LOG.info("[devLossFunc] loss=" + loss + " nDev=" + dev.size() + " for conf=" + conf.name);
        return loss;
      }
    };
  }

  /**
   * Adds a stopping condition based on the dev set performance.
   */
  public void train2(Reranker m, ItemProvider ip, Config conf) {
    // Split the data
    final ItemProvider train, dev;
    if (conf.tuneOnTrainingData) {
      LOG.info("[train2] tuneOnTrainingData=true");
      train = ip;
      dev = new ItemProvider.Slice(ip, Math.min(ip.size(), conf.maxDev), rand);
    } else {
      LOG.info("[train2] tuneOnTrainingData=false, splitting data");
      conf.autoPropDev(ip.size());
      ItemProvider.TrainTestSplit trainDev =
          new ItemProvider.TrainTestSplit(ip, conf.propDev, conf.maxDev, rand);
      train = trainDev.getTrain();
      dev = trainDev.getTest();
    }
    LOG.info("[train2] nTrain=" + train.size() + " nDev=" + dev.size()
        + " for conf=" + conf.name);

    // Use dev data for stopping condition
    StoppingCondition.DevSet dynamicStopping = null;
    if (conf.allowDynamicStopping) {
      if (dev.size() == 0)
        throw new RuntimeException("no dev data!");
      LOG.info("[train2] adding dev set stopping on " + dev.size() + " examples");
      File rScript = new File("scripts/stop.sh");
      double alpha = 0.15d;  // Lower numbers mean stop earlier.
      double k = 25;         // Size of history
      DoubleSupplier devLossFunc = modelLossOnData(m, dev, conf);
      dynamicStopping = conf.addStoppingCondition(
          new StoppingCondition.DevSet(rScript, devLossFunc, alpha, k));
    } else {
      LOG.info("[train2] allowDynamicStopping=false leaving stopping condition as is");
    }

    try {
      // Train the model
      hammingTrain(m, train, dev, conf);
      LOG.info("[train2] done hammingTrain, params:");
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
    LOG.info("[train2] done conf=" + conf.name);
    LOG.info("[train2] times: " + timer);
    LOG.info("[train2] totally done conf=" + conf.name);
  }

  /**
   * Trains the model to minimize hamming loss. If onlyStatelss is true, then
   * Stateful params of the model will not be fit, but updates should be much
   * faster to solve for as they don't require any forwards/backwards pass.
   */
  public void hammingTrain(Reranker r, ItemProvider train, ItemProvider dev, Config conf)
      throws InterruptedException, ExecutionException {
    LOG.info("[hammingTrain] starting, conf=" + conf);
    String timerStr = "hammingTrain." + conf.name;
    timer.start(timerStr);

    ExecutorService es = null;
    if (conf.threads > 1) {
      String msg = "i'm pretty sure multi-threaded features do not work";
      LOG.warn(msg);
      assert false : msg;
      es = Executors.newWorkStealingPool(conf.threads);
    }
    TimeMarker t = new TimeMarker();
    double secsBetweenUpdates = 0.5 * 60d;
    boolean showTime = false;
    boolean showViolation = true;
    outer:
    for (int iter = 0; true; ) {
      for (int i = 0; i < train.size(); i += conf.batchSize) {

        // Batch step
        conf.tHammingTrain.start();
        double violation = hammingTrainBatch(r, es, train, conf, iter, timerStr);
        conf.tHammingTrain.stop();

        // Print some data every once in a while.
        // Nothing in this conditional should have side-effects on the learning.
        if (t.enoughTimePassed(secsBetweenUpdates)) {
          r.showWeights();
          if (showViolation)
            LOG.info("[hammingTrain] iter=" + iter + " trainViolation=" + violation);
          if (showTime) {
            Timer bt = timer.get(timerStr + ".batch", false);
            int totalUpdates = conf.stopping.estimatedNumberOfIterations();
            LOG.info(String.format(
                "[hammingTrain] estimate: completed %d of %d updates, %.1f minutes remaining",
                bt.getCount(), totalUpdates, bt.minutesUntil(totalUpdates)));
          }
        }

        // See if we should stop
        double tStopRatio = conf.tHammingTrain.totalTimeInSeconds()
            / conf.tStoppingCondition.totalTimeInSeconds();
        LOG.info("[hammingTrain] train/stop=" + tStopRatio
            + " threshold=" + conf.stoppingConditionFrequency);
        if (tStopRatio > conf.stoppingConditionFrequency) {
          LOG.info("[hammingTrain] evaluating the stopping condition");
          conf.tStoppingCondition.start();
          boolean stop = conf.stopping.stop(iter, violation);
          conf.tStoppingCondition.stop();
          if (stop) {
            LOG.info("[hammingTrain] stopping due to " + conf.stopping);
            break outer;
          }
        }

        // See if we should re-estimate the learning rate.
        double tLrEstRatio = conf.tHammingTrain.totalTimeInSeconds()
            / conf.tLearningRateEstimation.totalTimeInSeconds();
        LOG.info("[hammingTrain] train/lrEstimate=" + tLrEstRatio
            + " threshold=" + conf.estimateLearningRateFreq);
        if (tLrEstRatio > conf.estimateLearningRateFreq) {
          LOG.info("[hammingTrain] restimating the learning rate");
          conf.tLearningRateEstimation.start();
          LOG.info("[hammingTrain] re-estimating the learning rate");
          estimateLearningRate(r, train, dev, conf);
          conf.tLearningRateEstimation.stop();
        }

        iter++;
      }
      conf.calledEveryEpoch.accept(iter);
    }
    if (es != null)
      es.shutdown();

    LOG.info("[hammingTrain] telling Params that training is over");
    r.getStatelessParams().doneTraining();
    r.getStatefulParams().doneTraining();
    r.getPruningParams().doneTraining();

    LOG.info("[hammingTrain] times:\n" + timer);
    timer.stop(timerStr);
  }

  /**
   * Returns the average violation over this batch.
   */
  private double hammingTrainBatch(
      Reranker r,
      ExecutorService es,
      ItemProvider ip,
      Config conf,
      int iter,
      String timerStrPartial) throws InterruptedException, ExecutionException {
    Timer tmv = timer.get(timerStrPartial + ".mostViolated", true).setPrintInterval(10).ignoreFirstTime();
    Timer to = timer.get(timerStrPartial + ".oracle", true).setPrintInterval(10).ignoreFirstTime();
    Timer t = timer.get(timerStrPartial + ".batch", true).setPrintInterval(10).ignoreFirstTime();
    t.start();

    // Compute the batch
    int n = ip.size();
    int[] batch;
    if (conf.batchSize == 0) {
      batch = new int[n];
      for (int i = 0; i < batch.length; i++)
        batch[i] = i;
    } else {
      batch = new int[conf.batchSize];
      for (int i = 0; i < batch.length; i++)
        batch[i] = rand.nextInt(n);
    }

    // Compute updates for the batch
    boolean verbose = false;
    List<Update> finishedUpdates = new ArrayList<>();
    if (es == null) {
      if (verbose)
        LOG.info("[hammingTrainBatch] running serial");
      for (int idx : batch) {
        FNParse y = ip.label(idx);
        List<Item> rerank = ip.items(idx);
        State init = State.initialState(y, rerank);
        if (verbose)
          LOG.info("[hammingTrainBatch] submitting " + idx);
        Update u = r.hasStatefulFeatures() || conf.forceGlobalTrain
          ? r.getFullUpdate(init, y, conf.oracleMode, rand, to, tmv)
          : r.getStatelessUpdate(init, y);
        finishedUpdates.add(u);
      }
    } else {
      if (verbose)
        LOG.info("[hammingTrainBatch] running with ExecutorService");
      List<Future<Update>> updates = new ArrayList<>();
      for (int idx : batch) {
        FNParse y = ip.label(idx);
        List<Item> rerank = ip.items(idx);
        if (verbose)
          LOG.info("[hammingTrainBatch] submitting " + idx);
        updates.add(es.submit(() -> r.getFullUpdate(
            State.initialState(y, rerank), y, conf.oracleMode, rand, null, null)));
      }
      for (Future<Update> u : updates)
        finishedUpdates.add(u.get());
    }
    if (verbose)
      LOG.info("[hammingTrainBatch] applying updates");
    assert finishedUpdates.size() == conf.batchSize
        || (finishedUpdates.size() == n && conf.batchSize == 0);

    // Apply the updates
    double learningRate = conf.learningRate.learningRate();
    double violation = new Update.Batch<>(finishedUpdates).apply(learningRate);
    conf.learningRate.observe(iter, violation, conf.batchSize);
    t.stop();
    return violation;
  }

  /**
   * First arg must be the job name (for tie-ins with tge) and the remaining are
   * key-value pairs.
   */
  public static void main(String[] args) throws IOException {
    assert args.length % 2 == 1;
    String jobName = args[0];
    ExperimentProperties config = new ExperimentProperties();
    config.putAll(Arrays.copyOfRange(args, 1, args.length), false);
    File workingDir = config.getOrMakeDir("workingDir");
    boolean useGlobalFeatures = config.getBoolean("useGlobalFeatures", true);
    boolean useEmbeddingParams = config.getBoolean("useEmbeddingParams", false); // else use TemplatedFeatureParams
    boolean useEmbeddingParamsDebug = config.getBoolean("useEmbeddingParamsDebug", false);
    boolean useFeatureHashing = config.getBoolean("useFeatureHashing", true);
    boolean testOnTrain = config.getBoolean("testOnTrain", false);
    boolean useCheatingParams = config.getBoolean("useCheatingParams", false);

    Reranker.COST_FN = config.getDouble("costFN", 1);
    LOG.info("[main] costFN=" + Reranker.COST_FN + " costFP=1");

    int nTrain = config.getInt("nTrain", 10);
    Random rand = new Random(9001);
    RerankerTrainer trainer = new RerankerTrainer(rand, workingDir);
    trainer.reporters = ResultReporter.getReporters(config);
    ItemProvider ip = new ItemProvider.ParseWrapper(DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences())
        .stream()
        //.filter(p -> p.numFrameInstances() <= 5)
        .limit(nTrain)
        .collect(Collectors.toList()));

    // Set the word shapes: features will try to do this otherwise, best to do ahead of time.
    if (config.getBoolean("precomputeShape", true)) {
      long t = System.currentTimeMillis();
      for (FNParse y : ip) {
        Sentence s = y.getSentence();
        int n = s.size();
        for (int i = 0; i < n; i++) {
          String shape = PosPatternGenerator.shapeNormalize(s.getWord(i));
          s.setShape(i, shape);
        }
      }
      LOG.info("[main] done computing word shapes, took "
          + (System.currentTimeMillis() - t)/1000d + " seconds");
    }

    // Data does not come with Stanford parses straight off of disk, add them
    if (config.getBoolean("addStanfordParses", true)) {
      long t = System.currentTimeMillis();
      LOG.info("[main] running Stanford parser on all training/test data...");
      ConcreteStanfordWrapper parser = new ConcreteStanfordWrapper();
      for (FNParse y : ip) {
        Sentence s = y.getSentence();

        // Get and set the Stanford constituency parse
        if (s.getStanfordParse(false) == null) {
          ConstituencyParse cp = parser.getCParse(s);
          s.setStanfordParse(cp);
        }

        // Get and set the Stanford basic dependency parse
        if (s.getBasicDeps(false) == null) {
          DependencyParse dp = parser.getBasicDParse(s);
          s.setBasicDeps(dp);
        }
      }
      LOG.info("[main] done parsing, took "
          + (System.currentTimeMillis() - t)/1000d + " seconds");
      // Note, could also precompute word shapes, but that is memoized on the fly
    }

    trainer.trainConf.oracleMode = OracleMode.valueOf(
        config.getString("oracleMode", "RAND").toUpperCase());

    trainer.pretrainConf.batchSize = config.getInt("pretrainBatchSize", 1);
    trainer.trainConf.batchSize = config.getInt("trainBatchSize", 8);

    trainer.performPretrain = config.getBoolean("performPretrain", false);

    // Set learning rate based on batch size
    int batchSizeThatShouldHaveLearningRateOf1 = config.getInt("lrBatchScale", 128);
    //trainer.pretrainConf.scaleLearningRateToBatchSize(batchSizeThatShouldHaveLearningRateOf1);
    trainer.trainConf.scaleLearningRateToBatchSize(batchSizeThatShouldHaveLearningRateOf1);

    // FOR DEBUGGING
//    trainer.trainConf.estimateLearningRateFreq = 0.1;
//    trainer.trainConf.stoppingConditionFrequency = 0.1;
//    Reranker.LOG_UPDATE = true;
//    RerankerTrainer.PRUNE_DEBUG = true;
//    Reranker.LOG_FORWARD_SEARCH = true;
//    trainer.pretrainConf.stopping = new StoppingCondition.Fixed(10);
//    trainer.pretrainConf.allowDynamicStopping = false;
//    trainer.trainConf.addStoppingCondition(new StoppingCondition.Time(15));
//    trainer.trainConf.addStoppingCondition(new StoppingCondition.Fixed(5000));
//    trainer.trainConf.allowDynamicStopping = false;

    // Show how many roles we need to make predictions for (in train and test)
    for (int i = 0; i < ip.size(); i++) {
      State s = State.initialState(ip.label(i));
      LOG.info("TK=" + s.numFrameRoleInstances());
    }

    // Split into train and test sets
    ItemProvider train, test;
    if (testOnTrain) {
      train = ip;
      test = ip;
      trainer.pretrainConf.tuneOnTrainingData = true;
      trainer.pretrainConf.maxDev = 25;
      trainer.trainConf.tuneOnTrainingData = true;
      trainer.trainConf.maxDev = 25;
    } else {
      double propTest = 0.25;
      int maxTest = 9999;
      ItemProvider.TrainTestSplit trainTest =
          new ItemProvider.TrainTestSplit(ip, propTest, maxTest, rand);
      train = trainTest.getTrain();
      test = trainTest.getTest();
    }

    LOG.info("[main] nTrain=" + train.size() + " nTest=" + test.size() + " testOnTrain=" + testOnTrain);

    final int hashBuckets = config.getInt("numHashBuckets", 2 * 1000 * 1000);
    final double l2Penalty = config.getDouble("l2Penalty", 1e-8);
    LOG.info("[main] using l2Penalty=" + l2Penalty);

    // What features to use (if features are being used)
    String fs = config.getBoolean("simpleFeatures", false)
        ? featureTemplates : featureTemplatesSearch;

    if (useCheatingParams) {
      // For debugging, invalidates a lot of other settings.
      // Note there is one wrinkle: CheatingParams extends Params.Stateless but
      // not Params.PruneThreshold, so it must produce positive or negative
      // scores for COMMIT actions, and ZERO must be used for tauParams.
      LOG.warn("[main] using cheating params with pruning threshold of 0");
      //trainer.tauParams = Params.PruneThreshold.Const.ZERO;
      trainer.tauParams = new CheatingParams(ip);
      CheatingParams cheat = trainer.addStatelessParams(new CheatingParams(ip));
      trainer.pretrainConf.dontPerformTuning();

      if (useGlobalFeatures) {
        LOG.info("[main] adding global cheating params");
        trainer.addGlobalParams(new GlobalFeature.Cheating(cheat, 0));
      }
    } else {
      // This is the path that will be executed when not debugging
      if (useEmbeddingParams) {
        LOG.info("[main] using embedding params");
        int embeddingSize = 2;
        EmbeddingParams ep = new EmbeddingParams(embeddingSize, l2Penalty, trainer.rand);
        ep.learnTheta(true);
        if (useEmbeddingParamsDebug)
          ep.debug(new TemplatedFeatureParams("embD", featureTemplates, hashBuckets), l2Penalty);
        trainer.statelessParams = ep;
      } else {
        LOG.info("[main] using features=" + fs);
        if (useFeatureHashing) {
          LOG.info("[main] using TemplatedFeatureParams with feature hashing");
          trainer.statelessParams =
              new TemplatedFeatureParams("statelessA", fs, l2Penalty, hashBuckets);
        } else {
          LOG.info("[main] using TemplatedFeatureParams with an Alphabet");
          trainer.statelessParams =
              new TemplatedFeatureParams("statelessH", fs, l2Penalty);
        }
      }

      // Setup tau/pruning parameters
      if (config.getBoolean("useDynamicTau", true)) {
        // Old way: very simple features
        //      double tauL2Penalty = config.getDouble("tauL2Penalty", 2e-2);
        //      double tauLearningRate = config.getDouble("taulLearningRate", Math.sqrt(trainer.trainConf.batchSize) / 10d);
        //      trainer.tauParams = new Params.PruneThreshold.Impl(tauL2Penalty, tauLearningRate);
        // Older way: very rich features.
        if (useFeatureHashing) {
          LOG.info("[main] using TemplatedFeatureParams with feature hashing for tau");
          trainer.tauParams =
              new TemplatedFeatureParams("tauA", fs, l2Penalty, hashBuckets);
        } else {
          LOG.info("[main] using TemplatedFeatureParams with an Alphabet for tau");
          trainer.tauParams =
              new TemplatedFeatureParams("tauH", fs, l2Penalty);
        }
      } else {
        LOG.warn("[main] you probably don't want to use constante params for tau!");
        trainer.tauParams = Params.PruneThreshold.Const.ZERO;
      }

      if (useGlobalFeatures) {
        double globalL2Penalty = config.getDouble("globalL2Penalty", 1e-2);
        LOG.info("[main] using global features with l2p=" + globalL2Penalty);
        if (config.getBoolean("useRoleCooc", false)) {
          trainer.addGlobalParams(
              new GlobalFeature.RoleCooccurenceFeatureStateful(globalL2Penalty));
        }

        trainer.addGlobalParams(
            new GlobalFeature.ArgOverlapFeature(globalL2Penalty));

        trainer.addGlobalParams(
            new GlobalFeature.SpanBoundaryFeature(globalL2Penalty));
      }
    }

    // Train
    LOG.info("[main] starting training, config:");
    config.store(System.out, null);
    Reranker model = trainer.train1(train);

    // Evaluate
    LOG.info("[main] done training, evaluating");
    Map<String, Double> perfResults = eval(model, test, "[main]");
    Map<String, String> results = new HashMap<>();
    results.putAll(ResultReporter.mapToString(perfResults));
    results.putAll(ResultReporter.mapToString(config));

    // Save the configuration
    OutputStream os = new FileOutputStream(new File(workingDir, "config.xml"));
    config.storeToXML(os, "ran on " + new Date());
    os.close();

    // Report results back to tge
    double mainResult = perfResults.get(BasicEvaluation.argOnlyMicroF1.getName());
    for (ResultReporter rr : trainer.reporters)
      rr.reportResult(mainResult, jobName, ResultReporter.mapToString(results));
  }

  private static final String featureTemplatesSearch = getFeatureSetFromFile("feaureSet.full.txt");
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

  public static String getFeatureSetFromFile(String path) {
    File f = new File(path);
    if (!f.isFile())
      throw new RuntimeException("not a file: " + path);
    try {
      BufferedReader br = new BufferedReader(new FileReader(f));
      String line = br.readLine();
      String[] toks = line.split("\t", 2);
      String features = toks[1];
      // Don't need the stage prefixes that we had before.
      features = features.replaceAll("RoleSpan(Labeling|Pruning)Stage-(regular|latent|none)\\*", "");
      br.close();
      return features;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
