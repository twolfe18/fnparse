package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

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

  private FNTagging frames;
  private Span[][] committed;       // indexed by [frameIndex][role]
  private boolean[][][][] possible; // indexed by [frameIndex][role][start][end]
  private int numPossible;
  private int movesApplied;

  public State(State copy) {
    frames = copy.frames;
    committed = new Span[copy.committed.length][];
    for (int i = 0; i < committed.length; i++) {
      if (copy.committed[i] == null) continue;
      committed[i] = Arrays.copyOf(copy.committed[i], copy.committed[i].length);
    }
    int I = copy.possible.length;
    possible = new boolean[I][][][];
    for (int i = 0; i < possible.length; i++) {
      int J = copy.possible[i].length;
      possible[i] = new boolean[J][][];
      for (int j = 0; j < J; j++) {
        if (copy.possible[i][j] == null)
          continue;
        int K = copy.possible[i][j].length;
        possible[i][j] = new boolean[K][];
        for (int k = 0; k < K; k++) {
          int L = copy.possible[i][j][k].length;
          possible[i][j][k] = new boolean[L];
          System.arraycopy(copy.possible[i][j][k], 0, possible[i][j][k], 0, L);
        }
      }
    }
    numPossible = copy.numPossible;
    movesApplied = copy.movesApplied;
  }

  public State(FNTagging frames) {
    this.movesApplied = 0;
    this.frames = frames;
    int t = frames.numFrameInstances();
    int n = frames.getSentence().size();
    numPossible++;
    possible = new boolean[t][][][];
    committed = new Span[t][];
    for (int fi = 0; fi < t; fi++) {
      Frame f = frames.getFrameInstance(fi).getFrame();
      int K = f.numRoles();
      possible[fi] = new boolean[K][n][n+1];
      committed[fi] = new Span[K];
      for (int k = 0; k < K; k++) {
        for (int i = 0; i < n; i++) {
          for (int j = 0; j <= n; j++) {
            boolean poss = i < j && !overlapsATarget(i, j);
            possible[fi][k][i][j] = poss;
            if (poss) numPossible++;
          }
        }
      }
    }
  }

  /** TODO rebuild one of these with a BitSet */
  interface StateIndex {
    public boolean possible(int t, int k, int start, int end);
    // ah, i should make this one big bitset
    // that doesn't solve the problem of how to find the next 1, oh wait, yes it does
    // N^2 * T * K
    // lets say for a big sentence: 50^2 * 30 * 10 bits < 94 kbytes
    // L1 cache is typically 64 kbytes, L2 is ~256K-1M

    // While this is nice, it is not the solution to my problems!
    // The only thing that is going to make the slightest of differences is how
    // many function evaluations I make.
    // Since the number of options is very large, I either need to make coarser
    // decisions OR know which decisions can be ignored at first.
    // state: {possible actions}
    // HOME: {open(t), finalize(t)}
    // ok, i sketched these and there are many ways to decompose the action space
    //
    // i think the real question is how to train this damn thing!
    // even if I give a value for every state (no learning there), and I do
    // epsilon-greedy action selection, it will take an extremely long time for
    // the model to learn that there are any other useful actions than
    // "predict nullSpan for all t and k"
    // I need to be able to show the model what the good choices are, and make
    // sure that it attempts to learn them.
    // I'm not comfortable with my ability to write an oracle that can always
    // choose the best action (I would prefer to learn this), but perhaps I'm
    // more comfortable with writing a state value function.
    // If I could give an oracle that gives a diverse set of actions to apply,
    // then score them with the state value function, then write a loss function
    // such that the model has to reproduce those values/relations/ranking...

    // I think what I'm onto is a little bit like NCE for a larger state space.
    // NCE was made for cases where there are a lot of states to normalize over
    // do do proper MLE. This RL business is worse than structured prediction
    // though, because you not only have the same large state space (in the
    // sense of space of labelings) that you had before you also have a huge
    // number of ways to get to each label.
  }

  public FNParse decode() {
    List<FrameInstance> fis = new ArrayList<>();
    for (int t = 0; t < frames.numFrameInstances(); t++) {
      FrameInstance fi = frames.getFrameInstance(t);
      Frame f = fi.getFrame();
      fis.add(FrameInstance.newFrameInstance(f, fi.getTarget(), committed[t], getSentence()));
    }
    return new FNParse(getSentence(), fis);
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
