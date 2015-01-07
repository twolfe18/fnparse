package edu.jhu.hlt.fnparse.rl;

import static org.junit.Assert.*;

import org.junit.Test;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.rl.params.PriorScoreParams;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateful;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer;

public class RerankerTest {
  private ItemProvider ip = Reranker.getItemProvider();
  private final int k = 10;

  /**
   * {@link edu.jhu.hlt.fnparse.rl.params.PriorScoreParams} will return -infinity
   * for items that are not in the pruned k-best (received from the original parser.
   * Oracle should still find something sensible.
   */
  @Test(timeout = k * 3000)
  public void oracleCanFindPathThroughSubsetOfItems() {
    int beamWidth = 10;
    PriorScoreParams theta = new PriorScoreParams(ip, true);
    Reranker r = new RerankerTrainer().train(Stateful.NONE, theta, beamWidth, ip);
    int n = Math.min(k, ip.size());
    for (int i = 0; i < n; i++) {
      FNParse y = ip.label(i);
      if (y.numFrameInstances() > 10)
        continue;
      StateSequence ss = r.oracle(y);
      assertTrue(Double.isFinite(ss.getScore()));
    }
  }
}
