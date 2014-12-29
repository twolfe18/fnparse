package edu.jhu.hlt.fnparse.rl;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * A dense and efficient bijection between the integers and (t,k,span).
 * Different indexes support different types of updates efficiently.
 * 
 * @author travis
 */
public interface StateIndex {

  public void update(Action a);

  public int index(int t, int k, int start, int end);
  // TODO inverse

  // TODO include other methods like eliminate all possibilities that match
  // pieces of (t,k,start,end), e.g. "all spans more than 10 words from t"
  // TODO this means that the bitset will need to be internal to this class.

  static class SpanMajor implements StateIndex {
    private int n;
    private int TK;
    private int[] Toffset;
    public SpanMajor(List<FrameInstance> fis, int n) {
      this.n = n;
      Toffset = new int[fis.size()];
      TK = 0;
      for (int i = 0; i < Toffset.length; i++) {
        Toffset[i] = TK;
        int k = fis.get(i).getFrame().numRoles();
        TK += k;
      }
    }
    @Override
    public int index(int t, int k, int start, int end) {
      assert start >= 0 && start < end && end <= n;
      int tk = Toffset[t] + k;
      int sk = n * start + end;
      return sk * TK + tk;
    }
  }
  
  static class RoleMajor implements StateIndex {
    // TODO
  }

}