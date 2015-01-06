package edu.jhu.hlt.fnparse.rl;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameArgInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * A dense and efficient bijection between the integers and (t,k,span).
 * Different indexes support different types of updates efficiently.
 * 
 * @author travis
 */
public interface StateIndex {

  /**
   * Returns a BitSet which reflects the state after applying the given action.
   * 
   * @deprecated this has now moved to ActionType.apply and unapply
   */
  public BitSet update(Action a, BitSet currentState);

  /**
   * Returns the index of the given (t,k,span).
   * 
   * You cannot pass in "special values" for start and end (see ASpan),
   * must be valid spans.
   */
  public int index(int t, int k, int start, int end);

  /**
   * Inverse of index()
   */
  public TKS lookup(int index);

  public static class TKS {
    public final int t, k, start, end;
    public TKS(int t, int k, int start, int end) {
      this.t = t;
      this.k = k;
      this.start = start;
      this.end = end;
    }
    public String toString() {
      return "t=" + t + " k=" + k + " start=" + start + " end=" + end;
    }
  }

  public int sentenceSize();
  public int numFrameInstances();
  public int numRoles(int t);

  // TODO include other methods like eliminate all possibilities that match
  // pieces of (t,k,start,end), e.g. "all spans more than 10 words from t"
  // TODO this means that the bitset will need to be internal to this class.

  /**
   * @deprecated
   */
  public static BitSet naiveUpdate(Action a, BitSet currentState, StateIndex si) {
    assert false : "use ActionType.apply";
    BitSet next = new BitSet(currentState.cardinality());
    next.xor(currentState);
    if (a.mode == ActionType.COMMIT.getIndex() || a.mode == ActionType.COMMIT_AND_PRUNE.getIndex()) {

      // Commit to this (t,k)
      int n = si.sentenceSize();
      for (int i = 0; i < n; i++)
        for (int j = i + 1; j <= n; j++)
          next.set(si.index(a.t, a.k, i, j), false);

      // Rule out other spans
      if (a.mode == ActionType.COMMIT_AND_PRUNE.getIndex()) {
        assert a.start >= 0;
        assert a.width() > 1;
        for (int t = 0; t < si.numFrameInstances(); t++) {
          for (int k = 0; k < si.numRoles(t); k++) {

            if (t == a.t && k == a.k)
              continue;

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

    public boolean debug = false;
    public Logger LOG = Logger.getLogger(SpanMajor.class);

    public SpanMajor(FNTagging frames) {
      this(frames.getFrameInstances(), frames.getSentence().size());
    }

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
      assert (start >= 0 && start < end && end <= n)
          || (start == Span.nullSpan.start && end == Span.nullSpan.end);

      // NOTE: It would be nice if we could, for the purpose of this encoding,
      // use [start,end] instead of [start,end), so that end < n.
      // HOWEVER, we want to support nullSpan=(0,0), so instead we'll pretend
      // n is really n+1.

      int tk = Toffset[t] + k;
      // TODO come up with a dense packing for n*(n-1)/2
      int sk = (n+1) * start + end;
      int index = sk * TK + tk;
      if (debug) {
        LOG.info("[index] t=" + t + " k=" + k + " start=" + start + " end=" + end);
        LOG.info("[index] Toffset=" + Arrays.toString(Toffset));
        LOG.info("[index] TK=" + TK + " tk=" + tk);
        LOG.info("[index] (n+1)=" + (n+1) + " sk=" + sk);
        LOG.info("[index] index=" + index);
      }
      return index;
    }

    @Override
    public TKS lookup(int index) {
      int tk = index % TK;
      int sk = index / TK;

      int start = sk / (n+1);
      int end = sk % (n+1);

      int t = 0;
      while (t < Toffset.length - 1
          && !(Toffset[t] <= tk && tk < Toffset[t + 1])) {
        t++;
      }
      assert Toffset[t] <= tk && (t == Toffset.length - 1 || tk < Toffset[t + 1]);
      int k = tk - Toffset[t];
      assert k < numRoles(t);

      if (debug) {
        LOG.info("[lookup] index=" + index);
        LOG.info("[lookup] Toffset=" + Arrays.toString(Toffset));
        LOG.info("[lookup] TK=" + TK + " tk=" + tk);
        LOG.info("[lookup] (n+1)=" + (n+1) + " sk=" + sk);
        LOG.info("[lookup] t=" + t + " k=" + k + " start=" + start + " end=" + end);
      }

      return new TKS(t, k, start, end);
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