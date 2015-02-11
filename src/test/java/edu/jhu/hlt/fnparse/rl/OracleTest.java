package edu.jhu.hlt.fnparse.rl;

import static org.junit.Assert.*;

import java.util.Collection;
import java.util.Random;

import org.apache.log4j.Logger;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.BFunc;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.ForwardSearch;
import edu.jhu.hlt.fnparse.util.FNDiff;

/**
 * Ensure that ForwardSearch/oracle returns the gold FNParse, or figure out what
 * to do when this doesn't happen!
 *
 * @author travis
 */
public class OracleTest {
  public static final Logger LOG = Logger.getLogger(OracleTest.class);
  private Random rand = new Random(9001);

  @Test
  public void test0() {
    Params.PruneThreshold tau = Params.PruneThreshold.Const.ZERO;
    Reranker model = new Reranker(
        Params.Stateful.NONE, Params.Stateless.NONE, tau, 1, rand);
    for (FNParse y : getParses()) {
      if (y.numFrameInstances() == 0)
        continue;
      boolean oracleSolveMax = false;
      BFunc bf = new BFunc.Oracle(y, oracleSolveMax);
      ForwardSearch oracleSearch = model.fullSearch(
          State.initialState(y), bf, oracleSolveMax, Params.Stateful.NONE);
      oracleSearch.run();
      StateSequence oracle = oracleSearch.getPath();
      State last = oracle.getCur();
      assertNotNull(last);
      FNParse yhat = last.decode();
      assertNotNull(yhat);
      if (!y.equals(yhat)) {
        LOG.info(FNDiff.diffArgs(y, yhat, true));
      }
      assertEquals(y, yhat);
    }
  }

  private Collection<FNParse> getParses() {
    return DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences())
        ;//.subList(0, 100);
  }
}
