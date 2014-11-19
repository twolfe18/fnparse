package edu.jhu.hlt.fnparse.inference.role.sequence;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Logger;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.util.FNDiff;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.hlt.fnparse.util.ModelIO;

public class RoleSequenceStageTest {
  public static final Logger LOG = Logger.getLogger(RoleSequenceStageTest.class);

  @Test
  public void test() {
    StdEvalFunc eval = BasicEvaluation.argOnlyMicroF1;
    List<FNParse> parses = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    String fs =
        "Role1 * Role2"
        + " + Role1 * Role2 * head1Pos"
        + " + Role1 * head1Word"
        + " + Role1 * head1Pos"
        + " + Role1 * head1ParentWord"
        + " + Role1 * head1ParentPos"
        + " + Role1 * head1GrandparentWord"
        + " + Role1 * head1GrandparentPos";
    GlobalParameters gp = new GlobalParameters();
    RoleSequenceStage rss = new RoleSequenceStage(gp, fs);
    for (FNParse y : parses) {
      LOG.info("\n\n\n\n");
      LOG.info("working on " + y.getId());
      FNTagging x = DataUtil.convertParseToTagging(y);
      gp.getFeatureNames().startGrowth();
      rss.scanFeatures(Arrays.asList(y));
      rss.train(Arrays.asList(x), Arrays.asList(y));
      FNParse yhat = rss.predict(Arrays.asList(x)).get(0);
      double perf = eval.evaluate(new SentenceEval(y, yhat));
      LOG.info(FNDiff.diffArgs(y, yhat, false));
      LOG.info("perf=" + perf);
      ModelIO.writeHumanReadable(rss.getWeights(), gp.getFeatureNames(),
          new File("/tmp/RoleSequenceStageTest-" + y.getId() + ".model"), false);
      assertTrue(perf >= 0.5d);
    }
  }
}
