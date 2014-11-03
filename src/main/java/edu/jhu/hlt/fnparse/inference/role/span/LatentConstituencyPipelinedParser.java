package edu.jhu.hlt.fnparse.inference.role.span;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.train.CrfObjective;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FNIterFilters;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.FPR;
import edu.jhu.hlt.fnparse.experiment.ParserTrainer;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.role.span.DeterministicRolePruning.Mode;
import edu.jhu.hlt.fnparse.inference.stages.OracleStage;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.util.DataSplitReader;
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

  private ParserParams params;
  private Stage<Sentence, FNTagging> frameId;
  private Stage<FNTagging, FNParseSpanPruning> rolePruning;
  private RoleSpanLabelingStage roleLabeling;

  public LatentConstituencyPipelinedParser(ParserParams params) {
    this.params = params;
    frameId = new OracleStage<>();
    if (params.useLatentConstituencies) {
      rolePruning = new RoleSpanPruningStage(params, this);
    } else {
      rolePruning = new DeterministicRolePruning(Mode.XUE_PALMER_HERMANN);
    }
    roleLabeling = new RoleSpanLabelingStage(params, this);
  }

  @Override
  public Alphabet<String> getAlphabet() {
    return params.getAlphabet();
  }

  public void dontDoAnyPruning() {
    if (!(rolePruning instanceof RoleSpanPruningStage))
      rolePruning = new RoleSpanPruningStage(params, this);
    ((RoleSpanPruningStage) rolePruning).dontDoAnyPruning();
  }

  public void useDeterministicPruning(DeterministicRolePruning.Mode mode) {
    rolePruning = new DeterministicRolePruning(mode);
  }

  @Override
  public void configure(Map<String, String> configuration) {
	  LOG.info("[configure] " + configuration);
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
    LOG.info("scanning features for " + parses.size() + " parses");
    params.getAlphabet().startGrowth();

    List<Sentence> sentences = DataUtil.stripAnnotations(parses);
    List<FNTagging> frames = DataUtil.convertParsesToTaggings(parses);
    frameId.scanFeatures(sentences, frames, 45, 10_000_000);

    List<FNParseSpanPruning> goldPrunes =
        FNParseSpanPruning.optimalPrune(parses);
    rolePruning.scanFeatures(frames, goldPrunes, 45, 10_000_000);

    // Here we have not trained a role pruning model yet, so we can't really
    // take scan the features of all of the decisions we will see at train
    // time. It would be problematic if we only scanned features on the
    // correct roles, possibly with the nullSpan as an option, because we
    // would lose most of the negative features. Instead we opt to take a
    // random sample of the negative decisions.
    double pIncludeNegativeSpan = 0.1d;
    List<FNParseSpanPruning> noisyPrunes =
        FNParseSpanPruning.noisyPruningOf(
            parses, pIncludeNegativeSpan, params.rand);
    roleLabeling.scanFeatures(noisyPrunes, parses, 45, 10_000_000);

    params.getAlphabet().stopGrowth();
    LOG.info("done scanning features");
  }

  public void learnWeights(List<FNParse> parses) {
    LOG.info("starting training on " + parses.size() + " parses");

    List<Sentence> sentences = DataUtil.stripAnnotations(parses);
    List<FNTagging> frames = DataUtil.convertParsesToTaggings(parses);
    frameId.train(sentences, frames);

    List<FNParseSpanPruning> goldPrunes =
        FNParseSpanPruning.optimalPrune(parses);
    rolePruning.train(frames, goldPrunes);

    // Here we really need to train the last stage of our pipeline to expect
    // the type of pruning that the previous stage will emit.
    //List<FNParseSpanPruning> hypPrunes = rolePruning
    //    .setupInference(frames, null).decodeAll();
    double pIncludeNegativeSpan = 0.1d;
    List<FNParseSpanPruning> hypPrunes =
        FNParseSpanPruning.noisyPruningOf(
            parses, pIncludeNegativeSpan, params.rand);
    roleLabeling.train(goldPrunes, parses);

    LOG.info("done training");
  }

  @Override
  public List<FNParse> parse(List<Sentence> sentences, List<FNParse> gold) {
    long start = System.currentTimeMillis();
    List<FNTagging> frames = frameId.setupInference(sentences, gold).decodeAll();
    List<FNParseSpanPruning> goldPrune = null;
    if (gold != null)
      goldPrune = FNParseSpanPruning.optimalPrune(gold);
    List<FNParseSpanPruning> prunes = rolePruning.setupInference(frames, goldPrune).decodeAll();
    //for (FNParseSpanPruning pr : prunes)
    //	LOG.info("[predict] pruning predicted AlmostFNParse: " + pr.describe());
    List<FNParse> parses = roleLabeling.setupInference(prunes, gold).decodeAll();
    long totalTime = System.currentTimeMillis() - start;
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
  public void saveModel(File directory) {
    LOG.info("saving model to " + directory.getPath());
    if (!directory.isDirectory())
      throw new IllegalArgumentException();
    frameId.saveModel(new File(directory, FRAME_ID_MODEL_NAME));
    rolePruning.saveModel(new File(directory, ROLE_PRUNE_MODEL_NAME));
    roleLabeling.saveModel(new File(directory, ROLE_LABEL_MODEL_NAME));
    if (ROLE_PRUNE_HUMAN_READABLE != null) {
      ModelIO.writeHumanReadable(rolePruning.getWeights(), getAlphabet(),
          new File(directory, ROLE_PRUNE_HUMAN_READABLE), true);
    }
    if (ROLE_LABEL_HUMAN_READABLE != null) {
      ModelIO.writeHumanReadable(roleLabeling.getWeights(), getAlphabet(),
          new File(directory, ROLE_LABEL_HUMAN_READABLE), true);
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

    ParserParams params = new ParserParams();
    params.setFeatureTemplateDescription(featureDesc);
    LatentConstituencyPipelinedParser p =
        new LatentConstituencyPipelinedParser(params);
    //p.useDeterministicPruning(Mode.XUE_PALMER_HERMANN);
    //p.dontDoAnyPruning();

    // Get the data
    List<FNParse> all = DataUtil.iter2list(
        new FNIterFilters.SkipSentences<FNParse>(
            FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences(),
            Arrays.asList("FNFUTXT1274640", "FNFUTXT1279095")));
    DataSplitReader dsr = new DataSplitReader(ParserTrainer.SENTENCE_ID_SPLITS);
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
        p.params.getAlphabet(),
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
            train, numTrainEval, params.rand);
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
