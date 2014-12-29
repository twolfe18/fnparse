package edu.jhu.hlt.fnparse.experiment.grid;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SemaforEval;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.role.span.DeterministicRolePruning.Mode;
import edu.jhu.hlt.fnparse.inference.role.span.LatentConstituencyPipelinedParser;
import edu.jhu.hlt.fnparse.inference.role.span.RoleSpanPruningStage;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;
import edu.jhu.hlt.fnparse.util.Describe;

/**
 * Given a working directory made by Runner, this loads the parser and can be
 * used to create (bootstrapped) learning curves (a special case of which is
 * to train on the full training set and report the final result).
 * 
 * @author travis
 */
public class FinalResults implements Runnable {
  public static final Logger LOG = Logger.getLogger(FinalResults.class);

  // Stores just the arg F1 score for all models
  // (this is meant for multiple jobs to append to this)
  public static final String RESULT_ACCUM_FILE = "allPerformance.txt";

  // These are retrain specific.
  // The non-retrain version (numTrain=-1) gets put in workingDir
  public static final String ALL_RESULTS_FILE = "performance.txt";
  public static final String SEMEVAL_RESULTS_FILE = "semevalPerformance.txt";
  public static final String PLAINTEXT_PREDICTIONS = "predictions.txt";

  // How many examples to retrain the model on.
  // -1 indicates that no model should be retrained, just use the loaded model.
  private int numTrain;
  private String mode;      // i.e. "span" or "head"
  private File workingDir;
  private Parser parser;
  private List<FNParse> testData;
  private List<FNParse> trainData;
  private Random rand = new Random(9001);

  public FinalResults(File workingDir, Random rand, String mode, int trainSize) {
    this(workingDir, rand, mode, trainSize, true);
  }

  public FinalResults(File workingDir, Random rand, String mode, int trainSize, boolean loadData) {
    if (!mode.equals("span") && !mode.equals("head"))
      throw new IllegalArgumentException();
    if (!workingDir.isDirectory())
      throw new IllegalArgumentException();
    this.workingDir = workingDir;
    this.mode = mode;
    this.numTrain = trainSize;
    if (loadData) {
      Iterator<FNParse> iter;
      iter = FileFrameInstanceProvider.dipanjantestFIP.getParsedSentences();
      testData = new ArrayList<>();
      while (iter.hasNext())
        testData.add(iter.next());

      // NOTE: fulltext (train) data should come first here
      // Later I'll take from this first, before LEX instances, as I don't think
      // they work as well.
      trainData = new ArrayList<>();
      iter = FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences();
      while (iter.hasNext())
        trainData.add(iter.next());
      LOG.info("[init] after train, trainData.size=" + trainData.size());
      iter = FileFrameInstanceProvider.fn15lexFIP.getParsedSentences();
      while (iter.hasNext()) {
        FNParse p = iter.next();
        if (!valid(p)) continue;
        trainData.add(p);
      }
      LOG.info("[init] after LEX, trainData.size=" + trainData.size());
    }
  }

  private static boolean valid(FNParse p) {
    for (FrameInstance fi : p.getFrameInstances()) {
      for (int k = 0; k < fi.getFrame().numRoles(); k++) {
        Span arg = fi.getArgument(k);
        assert arg != null;
        if (arg == Span.nullSpan)
          continue;
        if (arg.start < 0 || arg.end > p.getSentence().size()) {
          LOG.info("skipping " + p.getId() + " because "
              + fi.getFrame().getName() + "." + fi.getFrame().getRole(k)
              + " has span " + arg
              + " which is not valid in a sentence of length "
              + p.getSentence().size());
          return false;
        }
      }
    }
    return true;
  }

  /**
   * returns a directory to dump results in
   * if nTrain==-1, this is workingDir, otherwise it is nTrain-specific dir
   */
  private File train() {
    if (numTrain < 0) {
      LOG.info("[train] just usng loaded model");
      return workingDir;
    }

    // Choose the subset of the data to train on
    List<FNParse> trainSub;
    if (numTrain == 0) {
      LOG.info("[train] re-training on all " + trainData.size() + " examples");
      trainSub = trainData;
    } else {
      //LOG.info("[run] re-sampling " + numTrain + " examples to re-train on");
      //trainSub = DataUtil.resample(trainData, numTrain, rand);
      LOG.info("[train] takinig the first " + numTrain + " examples to "
          + "re-train on, first from fulltext, then LEX");
      trainSub = trainData.subList(0, numTrain);
    }

    // Where the model and results will go
    File resultsDir = new File(workingDir, "retrain-" + numTrain);
    if (!resultsDir.isDirectory()) resultsDir.mkdir();
    File retrainModelDir = new File(resultsDir, "model");

    // Train the model
    if (retrainModelDir.isDirectory()) {
      LOG.info("[train] since " + retrainModelDir.getPath()
          + " is a directory, loading the model from there instead of re-training");
      parser.loadModel(retrainModelDir);
    } else {
      LOG.info("[train] actually training on " + trainSub.size() + " examples");
      parser.configure("bpIters", "1");
      parser.configure("passes", "1");
      parser.train(trainSub);

      // Save the model
      LOG.info("[train] saving model to " + retrainModelDir.getPath());
      retrainModelDir.mkdir();
      parser.saveModel(retrainModelDir);
    }
    return resultsDir;
  }

  public void run() {
    LOG.info("[run] starting");
    LOG.info("[run] numTrain=" + numTrain);
    LOG.info("[run] trainData.size=" + trainData.size());
    LOG.info("[run] testData.size=" + testData.size());

    loadModel();

//    if (true) {
//      parser.configure("bpIters", "2");
//      compareLatentToSupervisedSyntax();
//      return;
//    }

    File resultsDir = train();

    // Evaluate the model (my evaluation)
    LOG.info("[run] predicting");
    List<Sentence> sentences = DataUtil.stripAnnotations(testData);
    List<FNParse> hyp = parser.parse(sentences, testData);
    /* The only reason to report one result is if you're stacking results from
     * multiple jobs into one file... and even then.
    LOG.info("[run] evaluating on " + testData.size()
        + " examples with " + evaluationFunc.getName());;
    double perf = evaluationFunc.evaluate(SentenceEval.zip(testData, hyp));
    File f = new File(resultsDir, RESULT_ACCUM_FILE);
    LOG.info("[run] writing perf=" + perf + " numTrain=" + numTrain
        + " to " + f.getPath());
    try (FileWriter w = new FileWriter(f, true)) {
      w.append(String.format("%f\t%d\n", perf, numTrain));
    } catch (Exception e) {
      e.printStackTrace();
    }
    */
    LOG.info("[run] evaluating");
    Map<String, Double> results = BasicEvaluation.evaluate(testData, hyp);
    File f = new File(resultsDir, ALL_RESULTS_FILE);
    LOG.info("[run] writing all eval metrics to " + f.getPath());
    try (FileWriter w = new FileWriter(f)) {
      List<String> keys = new ArrayList<>();
      keys.addAll(results.keySet());
      Collections.sort(keys);
      for (String k : keys) {
        double v = results.get(k);
        LOG.info(String.format("[evaluate] %100s %f", k, v));
        w.append(String.format("%f\t%s\n", v, k));
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    // Evaluate the model (SEMAFOR/SemEval'07)
    LOG.info("[run] running SemEval'07 evaluation (via Semafor)");
    File sewd = new File(resultsDir, "semeval");
    if (!sewd.isDirectory()) sewd.mkdir();
    SemaforEval se = new SemaforEval(sewd);
    se.evaluate(testData, hyp, new File(resultsDir, SEMEVAL_RESULTS_FILE));

    // Dump predictions, for visual inspection
    dumpPlaintextPredictions(hyp, new File(resultsDir, PLAINTEXT_PREDICTIONS));

    compareLatentToSupervisedSyntax();

    LOG.info("[run] done");
  }

  private void compareLatentToSupervisedSyntax() {
    if (!(parser instanceof LatentConstituencyPipelinedParser)) {
      LOG.info("[compareLatentToSupervisedSyntax] unsupported parser type: "
          + parser.getClass().getName());
      return;
    }
    LatentConstituencyPipelinedParser p = (LatentConstituencyPipelinedParser) parser;
    final int k = 500;  // upper bound on how many parses to run this on
    List<FNParse> runOn = testData;
    if (runOn.size() > k)
      runOn = DataUtil.reservoirSample(runOn, k, rand);

    RoleSpanPruningStage pruning = (RoleSpanPruningStage) p.getPruningStage();
    boolean useMaxRecallOrig = pruning.useCkyDecoder();
    for (boolean useMaxRecall : Arrays.asList(true, false)) {
      pruning.useCkyDecoder(useMaxRecall);

      Mode m1 = Mode.STANFORD_CONSTITUENTS;
      p.compareLatentToSupervisedSyntax(runOn, m1, true);

      Mode m2 = LatentConstituencyPipelinedParser.DEFAULT_PRUNING_METHOD;
      if (m2 != m1)
        p.compareLatentToSupervisedSyntax(runOn, m2, false);

      p.compareLatentToSupervisedSyntax(runOn, Mode.RANDOM, false);
    }
    pruning.useCkyDecoder(useMaxRecallOrig);
  }

  private void dumpPlaintextPredictions(List<FNParse> hyp, File f) {
    try (FileWriter fw = new FileWriter(f)) {
      for (FNParse p : hyp) {
        fw.write(Describe.fnParse(p));
        fw.write("\n");
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public Parser getParser() {
    return parser;
  }

  public void loadModel() {
    LOG.info("[loadModel] from " + workingDir.getPath());
    if (mode.equals("span")) {
      parser = new LatentConstituencyPipelinedParser();
    } else if (mode.equals("head")) {
      PipelinedFnParser p = new PipelinedFnParser();
      p.useGoldFrameId();
      parser = p;
    } else {
      throw new RuntimeException("mode=" + mode);
    }

    // Read the config used to create this parser
    Map<String, String> configuration =
        readConfig(new File(workingDir, "results.txt"));
    parser.configure(configuration);

    // Now load the data for each stage
    // (given the correct stages have been configured)
    parser.loadModel(new File(workingDir, "trainDevModel"));

    // I think train forgets to turn this off
    // Don't want prediction adding features (memory leak)
    parser.getAlphabet().stopGrowth();

    LOG.info("[loadModel] done");
  }

  private static Map<String, String> readConfig(File f) {
    LOG.info("[readConfig] from " + f.getPath());
    if (!f.isFile())
      throw new IllegalArgumentException(f.getPath() + " is not a file");
    Map<String, String> configuration = new HashMap<>();
    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
      String result = r.readLine(); // see ResultReporter
      LOG.info("[readConfig] result line: \"" + result + "\"");
      while (r.ready()) {
        String[] tok = r.readLine().split("\t", 2);
        String old = configuration.put(tok[0], tok[1]);
        assert old == null;
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return configuration;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      System.err.println("please provide:");
      System.err.println("1) a working dir");
      System.err.println("2) a parser mode");
      System.err.println("3) a random seed");
      System.err.println("4) num train");
      System.err.println("   -1 means load the existing model");
      System.err.println("   0 means take all of the data without resampling");
      System.err.println("   >0 means take resampled subset of the data");
      return;
    }
    Logger.getLogger(ConstituencyTreeFactor.class).setLevel(Level.FATAL);
    File workingDir = new File(args[0]);
    String parserMode = args[1];
    Random r = new Random(Integer.valueOf(args[2]));
    int numTrain = Integer.valueOf(args[3]);
    new FinalResults(workingDir, r, parserMode, numTrain).run();
  }
}
