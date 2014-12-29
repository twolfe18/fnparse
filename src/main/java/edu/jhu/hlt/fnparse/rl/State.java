package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Learner.Adjoints;

/**
 * Represents a sentence, the frame-targets realized, and the constraints on the
 * frame-elements to be labeled. In this implementation, the frame-targets are
 * assumed given.
 *
 * AH, I think I know how to do this, index actions on (frame.getId, role).
 * I only ever plan to have a few of those actions, so this should be fine.
 * BUT this violates what I had planned for actions, namely the generalization
 * where actions can apply to all role (e.g. first pick the set of arguments
 * for *all* roles, then assign args).
 * 
 * @author travis
 */
public class State {

  /**
   * represents a constraint on the set of arguments a particular
   * (frame,target,role) can take on.
   * 
   * TODO generalize this further, but for now I want to get it working with
   * only COMMIT actions (i.e. choosing a span -- including nullSpan -- for a 
   * frame-role).
   */
  public static class Action {
    public static final int COMMIT = 0;   // use the span value of aspan
    //public static final int KEEP = 1;     // exclude all spans in aspan
    //public static final int PRUNE = 2;    // include all spans in aspan

    public int t;          // (frame,target)
    public int k;          // role
    public int mode;
    public ASpan aspan;

    public Action(int t, int k, int mode, ASpan aspan) {
      this.t = t;
      this.k = k;
      this.mode = mode;
      this.aspan = aspan;
    }

    public boolean includes(Span s) {
      assert aspan.start >= 0 : "add special semantics versions";
      return aspan.start <= s.start && aspan.end >= s.end;
    }

    public boolean matches(Span s) {
      return s.start == aspan.end && s.end == s.end;
    }

    public String toString() {
      String m = mode == COMMIT ? "COMMIT" : "???";
      return String.format("[Action(%s) t=%d k=%d %s]", m, t, k, aspan);
    }

    public String toString(State s) {
      FrameInstance fi = s.getFrameInstance(t);
      Frame f = fi.getFrame();
      String m = mode == COMMIT ? "COMMIT" : "???";
      return String.format("[Action(%s) frame=%s@%d role=%s: %s]",
          m, f.getName(), fi.getTarget().start, f.getRole(k), aspan);
    }
  }

  interface StateIndex {
    public int index(int t, int k, int start, int end);
    // TODO include other methods like eliminate all possibilities that match
    // pieces of (t,k,start,end), e.g. "all spans more than 10 words from t"
    // TODO this means that the bitset will need to be internal to this class.
  }
  static class SpanMajorStateIndex implements StateIndex {
    private int n;
    private int TK;
    private int[] Toffset;
    public SpanMajorStateIndex(List<FrameInstance> fis, int n) {
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
    // TODO inverse of index.
  }

  public static class StateSequence extends State {

    private StateSequence prev, next;
    private int movesApplied;

    // The action + features + computation performed when leaving this state
    // and transitioning to next.
    private Adjoints aNext;

    public StateSequence(FNTagging frames) {
      super(frames);
      prev = null;
    }

    public StateSequence(StateSequence prev) {
      super(prev);
      this.prev = prev;
    }

    public FNParse decode() {
      /*
    List<FrameInstance> fis = new ArrayList<>();
    for (int t = 0; t < frames.numFrameInstances(); t++) {
      FrameInstance fi = frames.getFrameInstance(t);
      Frame f = fi.getFrame();
      fis.add(FrameInstance.newFrameInstance(f, fi.getTarget(), committed[t], getSentence()));
    }
    return new FNParse(getSentence(), fis);
       */
      // TODO this will be implemented as 
      throw new RuntimeException("implement me");
    }
  }

  private FNTagging frames;
  private StateIndex stateIndex;
  private BitSet possible;

  public State(State copy) {
    this.frames = copy.frames;
    this.stateIndex = copy.stateIndex;
    this.possible = new BitSet(copy.possible.size());
    this.possible.or(copy.possible);
  }

  public State(FNTagging frames) {
    this.frames = frames;
    int n = frames.getSentence().size();
    this.stateIndex = new SpanMajorStateIndex(frames.getFrameInstances(), n);
    this.possible = new BitSet();
  }

  public double recall() {
    int recalled = 0, total = 0;
    for (int t = 0; t < frames.numFrameInstances(); t++) {
      FrameInstance fi = frames.getFrameInstance(t);
      Frame f = fi.getFrame();
      for (int k = 0; k < f.numRoles(); k++) {
        Span arg = fi.getArgument(k);
        if (arg == Span.nullSpan)
          continue;
        total++;
        if (possible(t, k, arg))
          recalled++;
      }
    }
    return ((double) recalled) / total;
  }

  public boolean possible(int fiIdx, int role, Span s) {
    Span c = committed[fiIdx][role];
    if (c != null)
      return c == s;
    return possible[fiIdx][role][s.start][s.end];
  }

  public FrameInstance getFrameInstance(int i) {
    return frames.getFrameInstance(i);
  }

  public int movesApplied() {
    return movesApplied;
  }

  public Sentence getSentence() {
    return frames.getSentence();
  }

  public int possibleCompletions() {
    return numPossible;
  }

  private boolean overlapsATarget(int start, int end) {
    Span s = Span.getSpan(start, end);
    for (int i = 0; i < frames.numFrameInstances(); i++) {
      Span t = frames.getFrameInstance(i).getTarget();
      if (s.overlaps(t) && !s.equals(t))
        return true;
    }
    return false;
  }

  public boolean overlapsAnArg(int start, int end) {
    Span s = Span.getSpan(start, end);
    for (int t = 0; t < frames.numFrameInstances(); t++) {
      Frame f = getFrameInstance(t).getFrame();
      int K = f.numRoles();
      for (int k = 0; k < K; k++) {
        Span c = committed[t][k];
        if (c != null && c != Span.nullSpan) {
          if (s.overlaps(c) && !s.equals(c))
            return true;
        }
      }
    }
    return false;
  }

  public Iterable<Action> actions() {
    List<Action> actions = new ArrayList<>();
    int n = getSentence().size();
    for (int t = 0; t < frames.numFrameInstances(); t++) {
      FrameInstance fi = frames.getFrameInstance(t);
      Frame f = fi.getFrame();
      for (int k = 0; k < f.numRoles(); k++) {
        Span comm = committed[t][k];
        if (comm != null)
          continue;
        actions.add(new Action(t, k, Action.COMMIT, new ASpan(ASpan.NULL_SPAN, -999)));
        for (int start = 0; start < n; start++) {
          for (int end = start + 1; end <= n; end++) {
            if (!possible[t][k][start][end])
              continue;
            actions.add(new Action(t, k, Action.COMMIT, new ASpan(start, end)));
          }
        }
      }
    }
    //List<Iterable<Action>> iters = new ArrayList<>();
    //import com.google.common.collect.Iterables;
    //return Iterables.concat(iters);
    return actions;
  }

  // goes to the next state
  public void apply(Action a) {
    if (a.mode != Action.COMMIT)
      throw new RuntimeException("implement me");

    // NOTE: can't really do efficient unapply because a location may have been
    // made illegal by some action other than the one you're trying to undo

    if (a.aspan.isNullSpan()) {
      committed[a.t][a.k] = Span.nullSpan;
    } else {
      committed[a.t][a.k] = a.aspan.getSpan();
    }
    int n = getSentence().size();
    for (int i = 0; i < n; i++)
      for (int j = i + 1; j <= n; j++)
        if (possible[a.t][a.k][i][j])
          numPossible--;
    possible[a.t][a.k] = null;

    // TODO precompute the set of spans that overlap with...
    for (int i = 0; i < n; i++) {
      for (int j = i + 1; j <= n; j++) {
        if (overlapsAnArg(i, j)) {
          for (int t = 0; t < frames.numFrameInstances(); t++) {
            int K = getFrameInstance(t).getFrame().numRoles();
            for (int k = 0; k < K; k++) {
              if (committed[t][k] != null)
                continue;
              if (possible[t][k][i][j])
                numPossible--;
              possible[t][k][i][j] = false;
            }
          }
        }
      }
    }

    movesApplied++;
  }

}
