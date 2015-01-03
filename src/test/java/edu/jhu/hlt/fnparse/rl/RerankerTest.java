package edu.jhu.hlt.fnparse.rl;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.rl.rerank.PriorScoreParams;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;

public class RerankerTest {

  private Random rand = new Random(9001);
  private ItemProvider ip = Reranker.getItemProvider();

  /**
   * {@link edu.jhu.hlt.fnparse.rl.rerank.PriorScoreParams} will return -infinity
   * for items that are not in the pruned k-best (received from the original parser.
   * Oracle should still find something sensible.
   */
  @Test
  public void oracleCanFindPathThroughSubsetOfItems() {
    Params theta = new PriorScoreParams(ip);
    Reranker r = new Reranker(theta, 500, rand);
    for (int i = 0; i < ip.size(); i++) {
      FNParse y = ip.label(i);
      StateSequence ss = r.oracle(y);
      assertTrue(Double.isFinite(ss.getScore()));
    }
  }

  @Test
  public void test0() {
    Reranker r = new Reranker();
    r.train(ip);
    List<FNParse> y = new ArrayList<>();
    List<FNParse> hyp = new ArrayList<>();
    for (int i = 0; i < ip.size(); i++) {
      y.add(ip.label(i));
      hyp.add(r.predict(DataUtil.convertParseToTagging(ip.label(i))));
    }
    BasicEvaluation.showResults("[test0]", BasicEvaluation.evaluate(y, hyp));
  }
}
