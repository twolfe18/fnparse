package edu.jhu.hlt.fnparse.experiment.grid;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
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
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SemaforEval;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.role.span.LatentConstituencyPipelinedParser;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;

/**
 * Given a working directory made by Runner, this loads the parser and can be
 * used to create (bootstrapped) learning curves (a special case of which is
 * to train on the full training set and report the final result).
 * 
 * @author travis
 */
public class FinalResults implements Runnable {
  public static final Logger LOG = Logger.getLogger(FinalResults.class);
  public static final String RESULTS_FILE = "finalResults.txt";
  public static final String SEMEVAL_RESULTS_FILE = "semevalResults.txt";

  public static void removeOldResults(File workingDir) {
    File f = new File(workingDir, RESULTS_FILE);
    if (f.isFile())
      f.delete();
  }

  // How many examples to retrain the model on.
  // -1 indicates that no model should be retrained, just use the loaded model.
  private int numTrain;
  private Random rand;      // vary this to get different bootstrap samples
  private String mode;      // i.e. "span" or "head"
  private File workingDir;
  private Parser parser;
  private List<FNParse> testData;
  private List<FNParse> trainData;
  private EvalFunc evaluationFunc = BasicEvaluation.argOnlyMicroF1;

  public FinalResults(File workingDir, Random rand, String mode, int trainSize) {
    if (!mode.equals("span") && !mode.equals("head"))
      throw new IllegalArgumentException();
    this.workingDir = workingDir;
    this.rand = rand;
    this.mode = mode;
    this.numTrain = trainSize;

    testData = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantestFIP.getParsedSentences());

    trainData = new ArrayList<>();
    Iterator<FNParse> iter;
    iter = FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences();
    while (iter.hasNext())
      trainData.add(iter.next());
    LOG.info("[init] after train, trainData.size=" + trainData.size());
    iter = FileFrameInstanceProvider.fn15lexFIP.getParsedSentences();
    outer:
    while (iter.hasNext()) {
      FNParse p = iter.next();
      for (FrameInstance fi : p.getFrameInstances()) {
        for (int k = 0; k < fi.getFrame().numRoles(); k++) {
          Span arg = fi.getArgument(k);
          assert arg != null;
          if (arg == Span.nullSpan)
            continue;
          if (arg.start < 0 || arg.end >= p.getSentence().size()) {
            LOG.info("skipping " + p.getId() + " because "
              + fi.getFrame().getName() + "." + fi.getFrame().getRole(k)
              + " has span " + arg
              + " which is not valid in a sentence of length "
              + p.getSentence().size());
            continue outer;
          }
        }
      }
      trainData.add(p);
    }
    LOG.info("[init] after LEX, trainData.size=" + trainData.size());
  }

  public void run() {
    LOG.info("[run] starting");
    LOG.info("[run] numTrain=" + numTrain);
    LOG.info("[run] trainData.size=" + trainData.size());
    LOG.info("[run] testData.size=" + testData.size());

    loadModel();

    if (numTrain >= 0) {
      List<FNParse> trainSub;
      if (numTrain == 0) {
        LOG.info("[run] re-training on all " + trainData.size() + " examples");
        trainSub = trainData;
      } else {
        LOG.info("[run] re-sampling " + numTrain + " examples to re-train on");
        trainSub = DataUtil.resample(trainData, numTrain, rand);
      }
      parser.configure("bpIters", "2");
      parser.configure("passes", "3");
      parser.train(trainSub);
    } else {
      LOG.info("[run] just usng loaded model");
    }

    LOG.info("[run] evaluating on " + testData.size()
        + " examples with " + evaluationFunc.getName());;
    List<Sentence> sentences = DataUtil.stripAnnotations(testData);
    List<FNParse> hyp = parser.parse(sentences, testData);
    double perf = evaluationFunc.evaluate(SentenceEval.zip(testData, hyp));
    File f = new File(workingDir, RESULTS_FILE);
    LOG.info("[run] writing perf=" + perf + " numTrain=" + numTrain
        + " to " + f.getPath());
    try (FileWriter w = new FileWriter(f, true)) {
      w.append(String.format("%f\t%d\n", perf, numTrain));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    LOG.info("[run] running SemEval'07 evaluation (via Semafor)");
    File sewd = new File(workingDir, "semeval");
    if (!sewd.isDirectory()) sewd.mkdir();
    SemaforEval se = new SemaforEval(sewd);
    se.evaluate(testData, hyp, new File(workingDir, SEMEVAL_RESULTS_FILE));

    LOG.info("[run] done");
  }

  private void loadModel() {
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
