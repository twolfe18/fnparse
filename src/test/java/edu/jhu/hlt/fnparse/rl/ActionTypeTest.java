package edu.jhu.hlt.fnparse.rl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.BitSet;
import java.util.List;
import java.util.Random;

import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.params.DenseFastFeatures;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;

public class ActionTypeTest {
  public static final Logger LOG = Logger.getLogger(ActionTypeTest.class);

  private final Random rand = new Random(9001);
  private final int thoroghness = 1;

  @Test
  public void test0() {
    for (FNParse y : testParses()) {
      for (ActionType at : ActionType.ACTION_TYPES) {
        int n = thoroghness * 5;
        LOG.info("[test0] testing " + at.getName() + " " + n + " times");
        for (int i = 0; i < n; i++)
          helper(at, y);
      }
    }
  }

  /**
   * @deprecated This test cannot be passed without a large change.
   * The problem is that apply is destructive to the point where unapply doesn't
   * have enough information to serve as an inverse. For example, consider an
   * action that rules out any span that overlaps with (x,y). Some of these
   * spans may already be ruled out for another reason, but by the time unapply
   * sees the set of possible items, it has no way of knowing if this action
   * make a given span impossible or it was impossible before this action was
   * applied.
   * To fix this, I would need much more information in Action, specifically
   * what items it ruled out (Action would need a BitSet of its own representing
   * the state of the previous state as well as form of the Action).
   * Perhaps this is reasonable if it were computed lazily, but I don't actually
   * think that we need compose(apply, unapply) = identity.
   */
  public void helper(ActionType at, FNTagging frames) {
    if (frames.numFrameInstances() == 0)
      return; // No (t,k) to test on

    // Weights are 0 (no training), so features don't really matter
    Reranker r = new Reranker(new DenseFastFeatures(), 1);
    State st0 = r.randomDecodingState(frames, rand);

    // Shouldn't matter if apply or unapply is called first,
    boolean applyFirst = rand.nextBoolean();

    // Apply and unapply should compose to the identity
    Action a;
    State st1, st2;
    if (applyFirst) {
      a = DataUtil.reservoirSampleOne(at.next(st0, null), rand);
      st1 = at.apply(a, st0);
      LOG.info("after applying " + a + " " + State.possibleDiff(st0, st1));
      st2 = at.unapply(a, st1);
      LOG.info("after unapplying " + a + " " + State.possibleDiff(st1, st2));
    } else {
      a = DataUtil.reservoirSampleOne(at.prev(st0), rand);
      st1 = at.unapply(a, st0);
      LOG.info("after unapplying " + a + " " + State.possibleDiff(st0, st1));
      st2 = at.apply(a, st1);
      LOG.info("after applying " + a + " " + State.possibleDiff(st1, st2));
    }
    assertSameState(st0, st2);

    // State.apply should work the same way
    State st1b = st0.apply(a, applyFirst);
    State st2b = st1b.apply(a, !applyFirst);
    assertSameState(st1, st1b);
    assertSameState(st2, st2b);
    assertSameState(st0, st2b);
  }

  public static void assertSameState(State st0, State st2) {
    BitSet b0 = st0.getPossible();
    BitSet b2 = st2.getPossible();
    if (!b0.equals(b2)) {
      Assert.assertTrue(st0.getStateIndex() == st2.getStateIndex());
      System.out.println(State.possibleDiff(b0, b2, st0.getStateIndex()));
      Assert.assertTrue(false);
    }
    Span[][] st0c = st0.getCommitted();
    Span[][] st2c = st2.getCommitted();
    assertEquals(st0c.length, st2c.length);
    for (int i = 0; i < st0c.length; i++)
      assertArrayEquals(st0c[i], st2c[i]);
  }

  private List<FNParse> testParses() {
    List<FNParse> ys = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    ys = ys.subList(0, 10);
    return ys;
  }
}
