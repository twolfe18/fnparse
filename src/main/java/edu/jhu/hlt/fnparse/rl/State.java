package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.StateIndex.TKS;
import edu.jhu.hlt.fnparse.rl.rerank.Item;

/**
 * Represents a sentence, the frame-targets realized, and the constraints on the
 * frame-elements (roles) to be labeled. In this implementation, the
 * frame-targets are assumed given.
 * 
 * @author travis
 */
public class State {
  public static final Logger LOG = Logger.getLogger(State.class);
  public static boolean PRUNE_SPANS = false;

  protected FNTagging frames;
  private StateIndex stateIndex;  // (t,k,span) => int for indexing in possible

  // Represents which spans a given t,k can be assigned to.
  // Does not represent (t,k,i,j) s.t. i=0,j=0 (i.e. nullSpan).
  // A (t,k) being assigned to nullSpan is only captured by committed[t][k] = nullSpan.
  private BitSet possible;

  // [committed[t][k] != null] => [\not\exists i,j s.t. possible(t,k,i,j)]
  // NOTE the direction of that implication!
  // It is the job of COMMIT.next to update committed if it loops over i,j and
  // finds no possible(t,k,i,j). This can happen because PRUNE.apply only loops
  // over the i,j that are pruned, not those that aren't!
  private Span[][] committed;

  public State(FNTagging frames, StateIndex stateIndex, BitSet possible, Span[][] committed) {
    this.frames = frames;
    this.stateIndex = stateIndex;
    this.possible = possible;
    this.committed = committed;
  }

  public static State initialState(FNTagging frames) {
    int n = frames.getSentence().size();
    StateIndex stateIndex = new StateIndex.SpanMajor(frames.getFrameInstances(), n);
    BitSet possible = new BitSet();
    int T = frames.numFrameInstances();
    Span[][] committed = new Span[T][];
    // Allow every possible Span for every (t,k)
    for (int t = 0; t < T; t++) {
      int K = frames.getFrameInstance(t).getFrame().numRoles();
      committed[t] = new Span[K];
      for (int k = 0; k < K; k++)
        for (int i = 0; i < n; i++)
          for (int j = i + 1; j <= n; j++)
            possible.set(stateIndex.index(t, k, i, j), true);
    }
    // Always allow nullSpan
    allowNullSpanForEveryRole(possible, stateIndex, committed);
    return new State(frames, stateIndex, possible, committed);
  }

  public static State initialState(FNTagging frames, List<Item> rerank) {
    int n = frames.getSentence().size();
    StateIndex stateIndex = new StateIndex.SpanMajor(frames.getFrameInstances(), n);
    BitSet possible = new BitSet();
    int T = frames.numFrameInstances();
    Span[][] committed = new Span[T][];
    for (int t = 0; t < T; t++) {
      int K = frames.getFrameInstance(t).getFrame().numRoles();
      committed[t] = new Span[K];
    }
    // Allow each of the items
    for (Item i : rerank) {
      Span s = i.getSpan();
      possible.set(stateIndex.index(i.t(), i.k(), s.start, s.end));
    }
    // Always allow nullSpan
    allowNullSpanForEveryRole(possible, stateIndex, committed);
    return new State(frames, stateIndex, possible, committed);
  }

  /** Sets nullSpan to be possible for all (t,k) */
  private static void allowNullSpanForEveryRole(
      BitSet possible, StateIndex stateIndex, Span[][] committed) {
    final int T = committed.length;
    final int s = Span.nullSpan.start;
    final int e = Span.nullSpan.end;
    for (int t = 0; t < T; t++) {
      final int K = committed[t].length;
      for (int k = 0; k < K; k++)
        possible.set(stateIndex.index(t, k, s, e));
    }
  }

  public static State finalState(FNParse parse) {
    int n = parse.getSentence().size();
    StateIndex stateIndex = new StateIndex.SpanMajor(parse.getFrameInstances(), n);
    BitSet possible = new BitSet();
    int T = parse.numFrameInstances();
    Span[][] committed = new Span[T][];
    for (int t = 0; t < T; t++) {
      FrameInstance fi = parse.getFrameInstance(t);
      int K = fi.getFrame().numRoles();
      committed[t] = new Span[K];
      for (int k = 0; k < K; k++) {
        Span gold = fi.getArgument(k);
        committed[t][k] = gold;
        for (int i = 0; i < n; i++) {
          for (int j = i + 1; j <= n; j++) {
            boolean p = i == gold.start && j == gold.end;
            possible.set(stateIndex.index(t, k, i, j), p);
          }
        }
      }
    }
    return new State(parse, stateIndex, possible, committed);
  }

  /**
   * Find the first (t,k) such that committed[t][k] == null. Used for forcing
   * left-right inference.
   *
   * @param tk should be a length 2 array, after calling will be populated with
   * a t value in the first index and a k value in the second.
   * @return true if it found an uncommitted (t,k) and false otherwise.
   */
  public boolean findFirstNonCommitted(int[] tk) {
    assert tk.length == 2;
    int tForce = -1, kForce = -1;
    for (int t = 0; t < committed.length; t++) {
      int K = committed[t].length;
      for (int k = 0; k < K; k++) {
        Span a = committed[t][k];
        if (a == null && tForce < 0 && kForce < 0) {
          // Set for the first time
          tForce = t;
          kForce = k;
        } else {
          // Check that we're only setting it exactly once
          assert (tForce < 0 && kForce < 0) // we haven't set it yet
          || (a == null);               // we're not resetting it
        }
      }
    }
    if (tForce >= 0 && kForce >= 0) {
      tk[0] = tForce;
      tk[1] = kForce;
      return true;
    } else {
      return false;
    }
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
        int idx = stateIndex.index(t, k, arg.start, arg.end);
        total++;
        if (possible.get(idx))
          recalled++;
      }
    }
    return ((double) recalled) / total;
  }

  public boolean possible(int t, int k, Span a) {
    return possible(t, k, a.start, a.end);
  }

  public boolean possible(int t, int k, int start, int end) {
    int idx = stateIndex.index(t, k, start, end);
    return possible.get(idx);
  }

  public BitSet getPossible() {
    return possible;
  }

  /** Doesn't return a copy! Make sure you don't modify this! */
  public Span[][] getCommitted() {
    return committed;
  }

  /**
   * Returns a span if one has been chosen for this role, or null otherwise.
   */
  public Span committed(int t, int k) {
    return committed[t][k];
  }

  // TODO if slow, an index can be maintained for this
  public int numCommitted() {
    int comm = 0;
    for (Span[] c : committed)
      for (Span s : c)
        if (s != null)
          comm++;
    return comm;
  }

  // TODO if slow, an index can be maintained for this
  public int numUncommitted() {
    int uncomm = 0;
    for (Span[] c : committed)
      for (Span s : c)
        if (s == null)
          uncomm++;
    return uncomm;
  }

  /**
   * Sets committed[t][k] = Span.nullSpan.
   * Should only really be called by COMMIT.next.
   */
  public void noPossibleItems(int t, int k) {
    assert committed[t][k] == null;
    committed[t][k] = Span.nullSpan;
  }

  /**
   * Adds all committed spans that are not nullSpan to addTo and returns it.
   */
  public <T extends Collection<Span>> T getCommittedSpans(T addTo) {
    int T = committed.length;
    for (int t = 0; t < T; t++) {
      for (Span s : committed[t]) {
        if (s != null && s != Span.nullSpan)
          addTo.add(s);
      }
    }
    return addTo;
  }

  /**
   * If this State is a final state (all (t,k) are committed), then this will
   * return the parse represented by this parse.
   */
  public FNParse decode() {
    Sentence s = getSentence();
    List<FrameInstance> fis = new ArrayList<>();
    int T = numFrameInstance();
    for (int t = 0; t < T; t++) {
      int K = committed[t].length;
      Span[] args = Arrays.copyOf(committed[t], K);
      for (int k = 0; k < K; k++)
        if (committed[t][k] == null)
          args[k] = Span.nullSpan;
      fis.add(FrameInstance.newFrameInstance(getFrame(t), getTarget(t), args, s));
    }
    return new FNParse(s, fis);
  }

  public Frame getFrame(int t) {
    return frames.getFrameInstance(t).getFrame();
  }

  public Span getTarget(int t) {
    return frames.getFrameInstance(t).getTarget();
  }

  public int numFrameInstance() {
    return frames.numFrameInstances();
  }

  /** aka TK */
  public int numFrameRoleInstances() {
    int TK = 0;
    for (FrameInstance fi : frames.getFrameInstances())
      TK += fi.getFrame().numRoles();
    return TK;
  }

  public FrameInstance getFrameInstance(int i) {
    return frames.getFrameInstance(i);
  }

  public Sentence getSentence() {
    return frames.getSentence();
  }

  public FNTagging getFrames() {
    return frames;
  }

  public StateIndex getStateIndex() {
    return stateIndex;
  }

  public Span[][] copyOfCommitted() {
    int T = committed.length;
    Span[][] c = new Span[T][];
    for (int t = 0; t < T; t++)
      c[t] = Arrays.copyOf(committed[t], committed[t].length);
    return c;
  }

  /**
   * Returns the state resulting from applying the given action to this state.
   * Doesn't modify this state.
   */
  public State apply(Action a, boolean forwards) {
    ActionType at = ActionType.ACTION_TYPES[a.mode];
    if (forwards)
      return at.apply(a, this);
    else {
      assert false : "we shouldn't be using this anymore";
      //return at.unapply(a, this);
      return null;
    }
  }

  public String show() {
    StringBuilder sb = new StringBuilder("State of " + frames.getId() + "\n");
    int T = committed.length;
    for (int t = 0; t < T; t++) {
      for (int k = 0; k < committed[t].length; k++) {
        Span a = committed[t][k];
        Span ga = frames.getFrameInstance(t).getArgument(k);
        String as = a == null ? "NULL" : a.shortString();
        String gs = ga.shortString();
        String p = ""+possible(t, k, ga);
        sb.append("t=" + t + " k=" + k + " committed=" + as + "\tgold=" + gs + "\tgoldPossible=" + p + "\n");
      }
    }
    return sb.toString();
  }

  public static String possibleDiff(State a, State b) {
    if (a.stateIndex != b.stateIndex)
      throw new IllegalArgumentException();
    return possibleDiff(a.possible, b.possible, a.stateIndex);
  }

  public static String possibleDiff(BitSet a, BitSet b, StateIndex si) {
    StringBuilder sb = new StringBuilder("diff:\n");
    if (a.cardinality() != b.cardinality()) {
      sb.append("using closed world assumption because cardinalities differ: "
          + a.cardinality() + " vs " + b.cardinality() + "\n");
    }
    BitSet diff = new BitSet(a.cardinality());
    diff.xor(a);  // diff = a
    diff.xor(b);  // diff = a ^ b
    for (int i = diff.nextSetBit(0); i >= 0; i = diff.nextSetBit(i+1)) {
      TKS d = si.lookup(i);
      //sb.append("diff@" + i + ": ");
      sb.append(a.get(i) ? "- " : "+ ");
      sb.append(d.toString());
      sb.append('\n');
    }
    return sb.toString();
  }
}
