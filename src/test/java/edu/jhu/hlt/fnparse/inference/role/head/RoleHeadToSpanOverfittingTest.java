package edu.jhu.hlt.fnparse.inference.role.head;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.TestingUtil;
import edu.jhu.hlt.fnparse.util.FNDiff;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.hlt.fnparse.util.ModelIO;

public class RoleHeadToSpanOverfittingTest {
  public static final Logger LOG = Logger.getLogger(
      RoleHeadToSpanOverfittingTest.class);

  @Test
  public void test() {
    ParserParams params = new ParserParams();
    params.useLatentConstituencies = false;
    // Use some over-fitting features
    String feats = "RoleHeadToSpanStage * frameRoleArg * head1Word * span1LeftWord * span1FirstWord * span1LastWord * span1RightWord * head2Word * span1Width/1 * span1Width/2 * span1Width/3";
    if (params.useLatentConstituencies) {
      feats += " + RoleHeadToSpanStage * span1IsConstituent * span1Width/1 * head1Word * span1FirstWord";
      feats += " + RoleHeadToSpanStage * span1IsConstituent * frameRoleArg";
    }
    params.setFeatureTemplateDescription(feats);

    RoleHeadToSpanStage stage = new RoleHeadToSpanStage(new GlobalParameters(), feats);
    // Make sure we're not pruning the best answer
    Map<String, String> conf = new HashMap<>();
    conf.put("maxArgRoleExpandLeft", "99");
    conf.put("maxArgRoleExpandRight", "99");
    conf.put("passes.RoleHeadToSpanStage", "100");
    conf.put("regularizer.RoleHeadToSpanStage", "999999999");
    stage.configure(conf);
    RoleHeadToSpanStage.SHOW_FEATURES = false;

    List<FNParse> parses = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    parses = TestingUtil.filterOutExamplesThatCantBeFit(parses);
    Collections.shuffle(parses, params.rand);
    int sample = 1;
    for (int offset = 0; offset + sample <= parses.size(); offset += sample) {
      List<FNParse> parsesSample = parses.subList(offset, offset + sample);
      List<FNParse> heads = DataUtil.convertArgumenSpansToHeads(
          parsesSample, params.headFinder);

      LOG.info("scanning features");
      params.getAlphabet().startGrowth();
      stage.scanFeatures(heads, parsesSample, 99, 99_999_999);
      LOG.info("training");
      stage.train(heads, parsesSample);
      LOG.info("predicting");
      List<FNParse> predicted = stage.predict(heads);

      double perf = BasicEvaluation.fullMicroF1.evaluate(
          SentenceEval.zip(parsesSample, predicted));
      System.out.println("perf = " + perf);
      if (perf < 0.95d) {
        LOG.info("noticed mistake...");
        RoleHeadToSpanStage.SHOW_FEATURES = true;
        params.getAlphabet().startGrowth();
        stage.scanFeatures(heads, parsesSample, 99, 99_999_999);
        for (int i = 0; i < predicted.size(); i++) {
          System.out.println("\n" + i);
          FNParse gold = parsesSample.get(i);
          FNParse hyp = predicted.get(i);
          System.out.println(FNDiff.diffArgs(gold, hyp, false));
        }
        ModelIO.writeHumanReadable(stage.getWeights(),
            params.getAlphabet(), new File("/tmp/argSpan.weights"), true);
        assertTrue(false);
      }
    }
  }

}
