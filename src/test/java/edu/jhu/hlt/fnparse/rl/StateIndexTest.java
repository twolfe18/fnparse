package edu.jhu.hlt.fnparse.rl;

import static org.junit.Assert.*;

import java.util.BitSet;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class StateIndexTest {

  private Random rand = new Random(9001);
  private int thoroughness = 1;

  private Span randSpan(int n) {
    int l = rand.nextInt(n);
    int r = rand.nextInt(n);
    if (l == r) {
      r = l + 1;
    } else if (l > r) {
      int t = l;
      l = r;
      r = t;
    }
    return Span.getSpan(l, r);
  }

  private List<FNParse> testParses() {
    List<FNParse> ys = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    ys = ys.subList(0, 10);
    return ys;
  }

  @Test
  public void testUpdate() {
    for (FNParse y : testParses()) {
      for (int iter = 0; iter < thoroughness * 10; iter++) {
        int n = y.getSentence().size();
        StateIndex si = new StateIndex.SpanMajor(y.getFrameInstances(), n);
        int T = y.numFrameInstances();
        for (int t = 0; t < T; t++) {
          int K = y.getFrameInstance(t).getFrame().numRoles();
          for (int k = 0; k < K; k++) {
            Span span = randSpan(n);
            Action a = new Action(t, k, span);
            BitSet poss = State.initialState(y).getPossible();
            int idx = si.index(t, k, span.start, span.end);
            assertEquals(true, poss.get(idx));
            BitSet poss2 = si.update(a, poss);
            assertNotEquals(poss, poss2);
            assertEquals(true, poss.get(idx));
            assertEquals(false, poss2.get(idx));
            assertPossibleRange(false, poss2, si, y, t, k);
            assertPossibleRange(true, poss, si, y, t, k);
          }
        }
      }
    }
  }

  /**
   * asserts that every span matching the given (t,k) has possible value value
   */
  public static void assertPossibleRange(boolean value, BitSet possible, StateIndex si, FNParse y, int t, int k) {
    String loc = "t=" + t + ",k=" + k;
    int n = y.getSentence().size();
    for (int left = 0; left < n; left++) {
      for (int right = left + 1; right <= n; right++) {
        int idx = si.index(t, k, left, right);
        boolean poss = possible.get(idx);
        String msg = loc + " t=" + t + ",k=" + k + ",s=" + left + "-" + right;
        assertEquals(msg, value, poss);
      }
    }
  }
}
