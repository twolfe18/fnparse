package edu.jhu.hlt.fnparse.experiment.grid;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadStage;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadToSpanStage;
import edu.jhu.hlt.fnparse.inference.role.span.RoleSpanLabelingStage;
import edu.jhu.hlt.fnparse.inference.role.span.RoleSpanPruningStage;
import edu.jhu.hlt.fnparse.util.Counts;
import edu.jhu.hlt.fnparse.util.HasId;
import edu.jhu.hlt.fnparse.util.KpTrainDev;
import edu.jhu.hlt.fnparse.util.ParserLoader;
import edu.jhu.prim.util.math.FastMath;

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
    FastMath.useLogAddTable = false;  // saw about 8% improvement, not worth it
    FrameIdStage.SHOW_FEATURES = true;
    RoleHeadStage.SHOW_FEATURES = false;
    RoleHeadToSpanStage.SHOW_FEATURES = false;
    RoleSpanPruningStage.SHOW_FEATURES = false;
    RoleSpanLabelingStage.SHOW_FEATURES = false;

    //PipelinedFnParser.ARG_ID_MODEL_HUMAN_READABLE = "argId.txt";
    //PipelinedFnParser.ARG_SPANS_MODEL_HUMAN_READABLE = "argSpans.txt";
    //LatentConstituencyPipelinedParser.ROLE_PRUNE_HUMAN_READABLE = "rolePrune.txt";
    //LatentConstituencyPipelinedParser.ROLE_LABEL_HUMAN_READABLE = "roleLab.txt";

    //Logger.getLogger(RoleSpanPruningStage.class).setLevel(Level.WARN);

    // TODO Talk to Matt about numerical instability in ConstituencyTreeFactor
    Logger.getLogger(ConstituencyTreeFactor.class).setLevel(Level.ERROR);

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

  // TODO compute statistics like:
  // how many total/per-frame args are predicted
  // histogram of the span widths predicted
  // how many unique frame-roles have positive predictions

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
    BasicEvaluation.showResults("[evaluate]", BasicEvaluation.evaluate(test, predicted));
    return f.evaluate(SentenceEval.zip(test, predicted));
  }

  private List<FNParse> getAllTrain(Random r) {
    List<FNParse> all = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    String lim = config.get("MaxTrainSize");
    if (lim != null) {
      int n = Integer.parseInt(lim);
      if (n < all.size()) {
        LOG.info("[run] limiting the train set from " + all.size()
            + " to " + n);
        all = DataUtil.reservoirSample(all, n, r);
      }
    }
    return all;
  }

  public static Counts<String> getFRCounts(Collection<FNParse> parses) {
    Counts<String> counts = new Counts<>();
    for (FNParse p : parses) {
      for (FrameInstance fi : p.getFrameInstances()) {
        Frame f = fi.getFrame();
        for (int k = 0; k < f.numRoles(); k++) {
          if (fi.getArgument(k) != Span.nullSpan)
            counts.increment(f.getName() + "." + f.getRole(k));
        }
      }
    }
    return counts;
  }

  public static <T extends HasId> List<T> overlap(Collection<T> a, Collection<T> b) {
    Set<String> as = new HashSet<>();
    for (T ai : a)
      as.add(ai.getId());
    List<T> overlap = new ArrayList<>();
    for (T bi : b)
      if (as.contains(bi.getId()))
        overlap.add(bi);
    return overlap;
  }

  private Counts<String> getFrameRoleCounts(List<FNParse> data) {
    Counts<String> c = new Counts<>();
    for (FNParse p : data) {
      for (FrameInstance fi : p.getFrameInstances()) {
        Frame f = fi.getFrame();
        for (int k = 0; k < f.numRoles(); k++) {
          Span arg = fi.getArgument(k);
          if (arg == Span.nullSpan) continue;
          String fr = f.getName() + "." + f.getRole(k);
          c.increment(fr);
        }
      }
    }
    return c;
  }
  public void reportTrainTestStats(List<FNParse> train, List<FNParse> test) {
    Counts<String> tr = getFrameRoleCounts(train);
    Counts<String> te = getFrameRoleCounts(test);
    LOG.info("                     train\ttest");
    LOG.info("#frame-roles-types  " + tr.numNonZero() + "\t" + te.numNonZero());
    LOG.info("#frame-roles-tokens " + tr.getTotalCount() + "\t" + te.getTotalCount());
    for (int i = 1; i <= 15; i++) {
      List<String> trs = tr.countIsAtLeast(i);
      LOG.info("#frame-roles-types with count >= " + i + "\t"
        + trs.size() + "\t" + te.countIsAtLeast(i).size());
      Set<String> trss = new HashSet<>();
      trss.addAll(trs);
      int hits = 0, total = 0;
      for (String s : te.countIsAtLeast(1)) {
        if (trss.contains(s))
          hits++;
        total++;
      }
      LOG.info("%test-frame-role-types with train-count >= " + i + "\t"
        + hits + "/" + total + " " + (100d*hits)/total + "%");
    }
  }

  public void run() {
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
        assert overlap(train, dev).size() == 0;
        reportTrainTestStats(train, dev);
        parser.train(train);
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
