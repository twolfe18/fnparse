package edu.jhu.hlt.fnparse.experiment.grid;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.role.span.RoleSpanPruningStage;
import edu.jhu.hlt.fnparse.util.KpTrainDev;
import edu.jhu.hlt.fnparse.util.ParserLoader;

/**
 * Takes a feature set (as a string)
 * 
 * Trains the best model it can, possibly sweeping some params in the process,
 * and evaluating using Kp-CV.
 * 
 * Optionally performs evaluation on a test set.
 * 
 * @author travis
 */
public class Runner {
  public static Logger LOG = Logger.getLogger(Runner.class);

  public static void main(String[] args) {
    Runner r = new Runner(args);
    r.run();
  }

  private static String parseIntoMap(String[] args, Map<String, String> config) {
    assert config.size() == 0;
    assert args.length % 2 == 1;
    String name = args[0];
    for (int i = 1; i < args.length; i += 2) {
      String oldValue = config.put(args[i], args[i + 1]);
      if (oldValue != null) {
        throw new RuntimeException(args[i] + " has at least two values: "
            + args[2] + " and " + oldValue);
      }
    }
    return name;
  }

  /**
   * Provide a random seed in the config with "randomSeed" -> "9001"
   */
  private static Random getRandom(Map<String, String> config) {
    String key = "randomSeed";
    String seedS = config.get(key);
    if (seedS == null)
      throw new RuntimeException("you must provide " + key + " in your config");
    int seed = 0;
    try {
      Integer.parseInt(seedS);
    } catch (NumberFormatException e) {
      throw new RuntimeException("seed must be an integer: " + seedS, e);
    }
    return new Random(seed);
  }

  /**
   * Provide a working directory to dump output with "workingDir" -> "/tmp/foo"
   */
  private static File getWorkingDir(Map<String, String> config) {
    String key = "workingDir";
    String wd = config.get(key);
    if (wd == null)
      throw new RuntimeException("you need to provide a working directory");
    File wdf = new File(wd);
    if (!wdf.isDirectory())
      wdf.mkdirs();
    assert wdf.isDirectory();
    return wdf;
  }

  private String name;
  private Map<String, String> config;

  public Runner(String[] args) {
    config = new HashMap<>();
    name = parseIntoMap(args, config);
  }

  private double evaluate(Parser parser, List<FNParse> test) {
    String key = "evaluationFunction";
    String evalFuncName = config.get(key);
    if (evalFuncName == null)
      throw new RuntimeException("you must provide " + key + " in your config");
    LOG.info("[run] using " + evalFuncName + " to evaluate");
    EvalFunc f = BasicEvaluation.getEvaluationFunctionByName(evalFuncName);
    if (f == null) {
      throw new RuntimeException("unknown evaluaiton function name: "
          + evalFuncName);
    }
    List<Sentence> sentences = DataUtil.stripAnnotations(test);
    List<FNParse> predicted = parser.parse(sentences, test);
    return f.evaluate(SentenceEval.zip(test, predicted));
  }

  private List<FNParse> getAllTrain(Random r) {
    List<FNParse> all = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    String lim = config.get("MaxTrainSize");
    if (lim != null) {
      int n = Integer.parseInt(lim);
      LOG.info("[run] limiting the train set from " + all.size() + " to " + n);
      all = DataUtil.reservoirSample(all, n, r);
    }
    return all;
  }

  public void run() {
    Logger.getLogger(RoleSpanPruningStage.class).setLevel(Level.WARN);
    LOG.info("[run] starting");
    Random rand = getRandom(config);
    List<ResultReporter> reporters = ResultReporter.getReporter(config);
    File workingDir = getWorkingDir(config);
    Parser parser = ParserLoader.instantiateParser(config);
    parser.configure(config);

    // Train and compute dev error, then phone home
    if (config.containsKey("KpTrainDev")) {
      String kpConfig = config.get("KpTrainDev");
      String[] kpConfigAr = kpConfig.split("[^\\d\\.]+");
      assert kpConfigAr.length == 2;
      int K = Integer.parseInt(kpConfigAr[0]);
      double p = Double.parseDouble(kpConfigAr[1]);
      LOG.info("[run] performing Kp training with K=" + K + ", p=" + p);
      List<FNParse> all = getAllTrain(rand);
      List<FNParse>[] splits = KpTrainDev.kpSplit(K, p, all, rand);
      List<FNParse> train = new ArrayList<>();
      List<FNParse> dev = new ArrayList<>();
      double perfSum = 0d;
      for (int k = 0; k < K; k++) {
        train.clear();
        dev.clear();
        train.addAll(splits[0]);
        for (int devSplit = 0; devSplit < K; devSplit++)
          (devSplit == k ? dev : train).addAll(splits[devSplit + 1]);
        for (FNParse pp : train)
          LOG.debug("training on " + pp.getId());
        parser.train(train);
        for (FNParse pp : dev)
          LOG.debug("testing on " + pp.getId());
        double perf = evaluate(parser, dev);
        LOG.info("[run] for the " + (k+1) + "th split, perf=" + perf);
        perfSum += perf;
      }
      double perf = perfSum / K;
      for (ResultReporter r : reporters)
        r.reportResult(perf, name, config);
      File trainDevModelDir = new File(workingDir, "trainDevModel");
      if (!trainDevModelDir.isDirectory())
        trainDevModelDir.mkdir();
      parser.saveModel(trainDevModelDir);
    }

    // Compute test error and phone home
    if (config.containsKey("test")) {
      List<FNParse> test = DataUtil.iter2list(
          FileFrameInstanceProvider.dipanjantestFIP.getParsedSentences());
      LOG.info("[run] testing on " + test.size() + " test sentences");
      double perf = evaluate(parser, test);
      for (ResultReporter r : reporters)
        r.reportResult(perf, name, config);
    }
    LOG.info("[run] done");
  }
}
