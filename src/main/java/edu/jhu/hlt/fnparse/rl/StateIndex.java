package edu.jhu.hlt.fnparse.rl;

import java.util.BitSet;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FrameInstance;

/**
 * A dense and efficient bijection between the integers and (t,k,span).
 * Different indexes support different types of updates efficiently.
 * 
 * @author travis
 */
public interface StateIndex {

  /**
   * Returns a BitSet which reflects the state after applying the given action.
   */
  public BitSet update(Action a, BitSet currentState);

  /**
   * Returns the index of the given (t,k,span).
   * 
   * You cannot pass in "special values" for start and end (see ASpan),
   * must be valid spans.
   */
  public int index(int t, int k, int start, int end);
  // TODO inverse

  public int sentenceSize();
  public int numFrameInstances();
  public int numRoles(int t);

  // TODO include other methods like eliminate all possibilities that match
  // pieces of (t,k,start,end), e.g. "all spans more than 10 words from t"
  // TODO this means that the bitset will need to be internal to this class.

  public static BitSet naiveUpdate(Action a, BitSet currentState, StateIndex si) {
    BitSet next = new BitSet(currentState.cardinality());
    next.xor(currentState);
    if (a.mode == Action.COMMIT || a.mode == Action.COMMIT_AND_PRUNE_OVERLAPPING) {

      // Commit to this (t,k)
      int n = si.sentenceSize();
      for (int i = 0; i < n; i++) {
        for (int j = i + 1; j <= n; j++) {
          boolean b = i == a.start && j == a.end;
          next.set(si.index(a.t, a.k, i, j), b);
        }
      }

      // Rule out other spans
      if (a.mode == Action.COMMIT_AND_PRUNE_OVERLAPPING) {
        assert a.start >= 0;
        assert a.width() > 1;
        for (int t = 0; t < si.numFrameInstances(); t++) {
          for (int k = 0; k < si.numRoles(t); k++) {
            // Spans that end in this span.
            for (int i = 0; i < a.start; i++) {
              for (int j = a.start; j < a.end; j++) {
                next.set(si.index(t, k, i, j), false);
              }
            }
            // Spans that start in this span.
            for (int i = a.start + 1; i < a.end; i++) {
              for (int j = a.end; j <= n; j++) {
                next.set(si.index(t, k, i, j), false);
              }
            }
          }
        }
      }

    } else {
      throw new RuntimeException("not supported");
    }
    return next;
  }

  static class SpanMajor implements StateIndex {
    private int n;
    private int TK;
    private int[] Toffset;
    private int[] K;
    private int T;
    public SpanMajor(List<FrameInstance> fis, int n) {
      this.n = n;
      T = fis.size();
      K = new int[T];
      Toffset = new int[T];
      TK = 0;
      for (int t = 0; t < T; t++) {
        Toffset[t] = TK;
        int k = fis.get(t).getFrame().numRoles();
        TK += k;
        K[t] = k;
      }
    }
    @Override
    public int index(int t, int k, int start, int end) {
      assert start >= 0 && start < end && end <= n;
      int tk = Toffset[t] + k;
      int sk = n * start + end;
      return sk * TK + tk;
    }
    @Override
    public BitSet update(Action a, BitSet currentState) {
      return naiveUpdate(a, currentState, this);
    }
    @Override
    public int sentenceSize() {
      return n;
    }
    @Override
    public int numFrameInstances() {
      return T;
    }
    @Override
    public int numRoles(int t) {
      return K[t];
    }
  }

//  static class RoleMajor implements StateIndex {
//  }

}