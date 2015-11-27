package edu.jhu.hlt.fnparse.rl;

import static org.junit.Assert.assertEquals;

import java.util.BitSet;
import java.util.List;

import org.apache.log4j.Logger;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.rl.StateIndex.TKS;
import edu.jhu.hlt.tutils.Span;

public class StateIndexTest {
  public static final Logger LOG = Logger.getLogger(StateIndexTest.class);

  private int thoroughness = 1;

  // TODO similar to testParses(), have a method called testIndices() which returns
  // a list of StateIndex's that should be tested (right now there is only one
  // implementation.
  private List<FNParse> testParses() {
    List<FNParse> ys = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    ys = ys.subList(0, 10);
    return ys;
  }

  @Test
  public void testIndexAndLookupComposeToIdentity() {
    for (FNParse y : testParses()) {
      StateIndex.SpanMajor si = new StateIndex.SpanMajor(y);
      //si.debug = true;
      int n = y.getSentence().size();
      int T = y.numFrameInstances();
      for (int iter = 0; iter < thoroughness * 10; iter++) {
        for (int t = 0; t < T; t++) {
          FrameInstance fi = y.getFrameInstance(t);
          int K = fi.getFrame().numRoles();
          for (int k = 0; k < K; k++) {
            for (int start = 0; start < n; start++) {
              for (int end = 0; end <= n; end++) {

                // Allow nullSpan
                boolean ns = Span.nullSpan.equals(start, end);
                if (!ns && start >= end)
                  continue;

                if (si.debug) LOG.info("");
                int idx = si.index(t, k, start, end);
                TKS tks = si.lookup(idx);
                if (si.debug) LOG.info("");
                assertEquals(t, tks.t);
                assertEquals(k, tks.k);
                assertEquals(start, tks.start);
                assertEquals(end, tks.end);
              }
            }
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
