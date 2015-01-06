package edu.jhu.hlt.fnparse.rl;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Random;

import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class StateTest {

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
  public void resultingFromCommits() {
    for (int iter = 0; iter < 10 * thoroughness; iter++) {
      for (FNParse y : testParses()) {
        int n = y.getSentence().size();
        int T = y.numFrameInstances();
        State s = State.initialState(y);
        for (int t = 0; t < T; t++) {
          int K = y.getFrameInstance(t).getFrame().numRoles();
          for (int k = 0; k < K; k++) {
            assertTrue(s.committed(t, k) == null);
            Span span = randSpan(n);
            Action a = new Action(t, k, ActionType.COMMIT.getIndex(), span);
            State s2 = s.apply(a, true);
            assertEquals(span, s2.committed(t, k));
          }
        }
      }
    }
  }

}
