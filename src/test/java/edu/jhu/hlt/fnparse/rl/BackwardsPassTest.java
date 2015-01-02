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

/**
 * Ensures that the backwards pass (from a final state working its way back to
 * an initial state) is a finite set of steps.
 *
 * Tests are similar to
 * {@link edu.jhu.hlt.fnparse.rl.rerank.Reranker#backward}
 *
 * @author travis
 */
public class BackwardsPassTest {
  public static final Logger LOG = Logger.getLogger(BackwardsPassTest.class);

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

    // This is how the parameters will be initialized during learning
    Params theta = new Params() {
      @Override public Adjoints score(State s, Action a) {
        return new Adjoints() {
          @Override public double getScore() { return 0d; }
          @Override public Action getAction() { return a; }
        };
      }
      @Override public void update(Adjoints a, double reward) {}
    };

    for (int iter = 0; iter < thoroughness * 10; iter++) {
      for (FNParse y : testParses()) {
        LOG.info("[noBranching] " + y.getId() + " iter " + iter);
        TransitionFunction trans = new TransitionFunction.Simple(y, theta);
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
          LOG.info("[noBranching] \t" + i + "/" + TK + ": " + a);
          String appSite = "t=" + a.t + " k=" + a.k;
          assertTrue(appSite, applicationSites.add(appSite));  // Simple only allows commits

          // TODO AHHH, the problem is the directionality of apply
          // I'm applying actions as if they are forward applies, and this
          // is doing backwards applies.
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

}
