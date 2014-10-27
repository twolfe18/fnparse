package edu.jhu.hlt.fnparse.experiment.grid;

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

  private static Map<String, String> parseIntoMap(String[] args) {
    Map<String, String> config = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      String oldValue = config.put(args[i], args[i + 1]);
      if (oldValue != null) {
        throw new RuntimeException(args[i] + " has at least two values: "
            + args[2] + " and " + oldValue);
      }
    }
    return config;
  }

  /**
   * You can use the key "resultReporter" -> "redis:host,channel,port"
   * or "resultReporter" -> "none", but something must be there.
   */
  private static ResultReporter getResultReporter(Map<String, String> config) {
    String key = "resultReporter";
    String desc = config.get(key);
    if (desc == null) {
      throw new RuntimeException("this configuration has no key for: " + key);
    } else {
      return ResultReporter.getReporter(desc);
    }
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

  private Map<String, String> config;

  public Runner(String[] args) {
    config = parseIntoMap(args);
  }

  private double evaluate(Parser parser, List<FNParse> test) {
    String key = "evaluationFunction";
    String evalFuncName = config.get(key);
    if (evalFuncName == null)
      throw new RuntimeException("you must provide " + key + " in your config");
    EvalFunc f = BasicEvaluation.getEvaluationFunctionByName(evalFuncName);
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
      all = DataUtil.reservoirSample(all, n, r);
    }
    return all;
  }

  public void run() {
    Logger.getLogger(RoleSpanPruningStage.class).setLevel(Level.WARN);
    LOG.info("[run] starting");
    Random rand = getRandom(config);
    ResultReporter reporter = getResultReporter(config);
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
          (devSplit == k ? dev : train).addAll(splits[k + 1]);
        parser.train(train);
        double perf = evaluate(parser, dev);
        perfSum += perf;
      }
      double perf = perfSum / K;
      reporter.reportResult(perf, config);
    }

    // Compute test error and phone home
    if (config.containsKey("test")) {
      List<FNParse> test = DataUtil.iter2list(
          FileFrameInstanceProvider.dipanjantestFIP.getParsedSentences());
      LOG.info("[run] testing on " + test.size() + " test sentences");
      double perf = evaluate(parser, test);
      reporter.reportResult(perf, config);
    }
    LOG.info("[run] done");
  }
}
