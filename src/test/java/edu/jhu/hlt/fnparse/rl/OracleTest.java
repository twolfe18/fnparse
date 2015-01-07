package edu.jhu.hlt.fnparse.rl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.log4j.Logger;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.rl.params.DenseFastFeatures;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateful;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateless;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;

/**
 * Ensures that the oracle problem (from a final state working its way back to
 * an initial state) is a finite set of steps.
 *
 * Tests are similar to
 * {@link edu.jhu.hlt.fnparse.rl.rerank.Reranker#oracle}
 *
 * @author travis
 */
public class OracleTest {
  public static final Logger LOG = Logger.getLogger(OracleTest.class);

  private Random rand = new Random(9001);
  private int thoroughness = 1;

  private List<FNParse> testParses() {
    List<FNParse> ys = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    ys = ys.subList(0, 10);
    return ys;
  }

  @Test
  public void noBranching() {
    for (FNParse y : testParses()) {
      for (int iter = 0; iter < thoroughness * 10; iter++) {
        //LOG.info("[noBranching] " + y.getId() + " iter " + iter);
        //TransitionFunction trans = new TransitionFunction.Simple(y, Stateful.NONE);
        TransitionFunction trans = new TransitionFunction.ActionDrivenTransitionFunction(Stateful.NONE, ActionType.COMMIT);
        State finalState = State.finalState(y);
        StateSequence init = new StateSequence(null, null, finalState, null);

        // One action for each (t,k) should get you to an initial state
        int TK = 0;
        for (FrameInstance fi : y.getFrameInstances())
          TK += fi.getFrame().numRoles();

        // Apply all of the undo actions
        Set<String> applicationSites = new HashSet<>();
        StateSequence frontier = init;
        for (int i = 0; i < TK; i++) {
          Iterable<StateSequence> prev = trans.previousStates(frontier);
          assertTrue(prev.iterator().hasNext());
          frontier = DataUtil.reservoirSampleOne(prev, rand);
          assertNotNull(frontier);
          Action a = frontier.getAction();
          //LOG.info("[noBranching] \t" + i + "/" + TK + ": " + a);
          String appSite = "t=" + a.t + " k=" + a.k;
          assertTrue(appSite, applicationSites.add(appSite));  // Simple only allows commits
        }

        // Check that frontier is an initial state.
        assertEquals(frontier.getScore(), 0d, 1e-6);
        assertTrue(frontier.getCur().getSentence() == y.getSentence());
        Iterable<StateSequence> shouldBeEmpty = trans.previousStates(frontier);
        for (StateSequence ss : shouldBeEmpty) {
          LOG.warn(ss.getAction());
        }
        assertTrue(!shouldBeEmpty.iterator().hasNext());
        int T = y.numFrameInstances();
        for (int t = 0; t < T; t++) {
          int K = y.getFrameInstance(t).getFrame().numRoles();
          for (int k = 0; k < K; k++) {
            assertTrue(frontier.getCur().committed(t, k) == null);
          }
        }
      }
    }
  }

  /**
   * When we run backward() we should get a valid path from an empty parse
   * (initial state) to the final parse.
   */
  @Test
  public void validPath() {
    Reranker r = new Reranker(new DenseFastFeatures(), Stateless.NONE, 100);
    for (FNParse y : testParses()) {
      if (y.numFrameInstances() == 0)
        continue;
      StateSequence pathStart = r.oracle(y);
      assertEquals(0, pathStart.getCur().numCommitted());
      StateSequence pathEnd = pathStart.getLast();
      State finalState = pathEnd.getCur();
      FNParse yy = finalState.decode();
      assertNotNull(yy);
      assertEquals(y, yy);
    }
  }

  /* This is a junk test that takes too long... :(
  @Test
  public void testMostViolated() {
    StdEvalFunc eval = BasicEvaluation.argOnlyMicroF1;
    Reranker r = new Reranker(new DenseFastFeatures(), Stateless.NONE, 10);
    for (FNParse y : testParses()) {
      if (y.numFrameInstances() == 0)
        continue;
      StateSequence mv = r.mostViolated(y, y);
      assertEquals(mv, mv.getLast());
      FNParse yMV = mv.getCur().decode();
      //FNParse yEmpty = new FNParse(y.getSentence(), Collections.emptyList());
      double loss = 1d - eval.evaluate(new SentenceEval(y, yMV));
      LOG.info("[testMostViolated] " + y.getId() + " loss=" + loss);
    }
  }
  */
}
