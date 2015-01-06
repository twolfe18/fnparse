package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.rl.params.CheatingParams;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer;

/**
 * If the algorithm is given an indicator feature for whether an item is in the
 * gold parse, it should be able to get 100% accuracy (quickly).
 *
 * @author travis
 */
public class LearningTest {
  private final Random rand = new Random(9001);

  /** Set CheatingParams weights by hand */
  @Test
  public void getsItRightWithNoTraining() {
    ItemProvider ip = Reranker.getItemProvider();
    List<FNParse> gold = ItemProvider.allLabels(ip, new ArrayList<>());

    CheatingParams theta = new CheatingParams(gold);
    theta.setWeightsByHand();

    int beamWidth = 10;
    Reranker model = new Reranker(theta, beamWidth);
    model.logMostViolated = true;
    List<FNParse> hyp = model.predict(DataUtil.convertParsesToTaggings(gold));
    Map<String, Double> r = BasicEvaluation.evaluate(gold, hyp);
    String desc = model + " with params set by hand";
    BasicEvaluation.showResults(desc, r);
    Assert.assertTrue(0.99d < r.get(BasicEvaluation.argOnlyMicroF1.getName()));
  }

  //@Test
  public void getsItRightAtAll() {
    getsItRight(20);
  }

  //@Test
  public void getsItRightQuickly() {
    getsItRight(1);
  }

  public void getsItRight(int iters) {
    int beamWidth = 100;
    RerankerTrainer trainer = new RerankerTrainer(rand, iters, 1, 1);
    ItemProvider ip = Reranker.getItemProvider();
    List<FNParse> gold = ItemProvider.allLabels(ip, new ArrayList<>());
    Reranker model = trainer.train(new CheatingParams(gold), beamWidth, ip);
    List<FNParse> hyp = model.predict(DataUtil.convertParsesToTaggings(gold));
    Map<String, Double> r = BasicEvaluation.evaluate(gold, hyp);
    String desc = model + " trained in " + iters + " epochs";
    BasicEvaluation.showResults(desc, r);
    Assert.assertTrue(0.99d < r.get(BasicEvaluation.argOnlyMicroF1.getName()));
  }
}
