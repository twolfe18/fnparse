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
 * frame-elements to be labeled. In this implementation, the frame-targets are
 * assumed given.
 * 
 * @author travis
 */
public class State {
  public static final Logger LOG = Logger.getLogger(State.class);
  public static boolean PRUNE_SPANS = true;

  protected FNTagging frames;
  private StateIndex stateIndex;  // (t,k,span) => int for indexing in possible
  private BitSet possible;
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

  public int numCommitted() {
    int comm = 0;
    for (Span[] c : committed)
      for (Span s : c)
        if (s != null)
          comm++;
    return comm;
  }

  public int numUncommitted() {
    int uncomm = 0;
    for (Span[] c : committed)
      for (Span s : c)
        if (s == null)
          uncomm++;
    return uncomm;
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
      for (int k = 0; k < K; k++)
        if (committed[t][k] == null)
          return null;
      fis.add(FrameInstance.newFrameInstance(getFrame(t), getTarget(t), committed[t], s));
    }
    return new FNParse(s, fis);
  }

  /**
   * For now just returns all spans (with a couple simple pruning heuristics).
   *
   * TODO this should be fully absorbed into TransitionFunction.
   */
  public Iterable<Span> naiveAllowableSpans(int t, int k) {
    // TODO consider disallowing spans that overlap with a previously committed
    // to argument (this can be done with features, but is much slower).
    // NOTE this is not really an issue with targets, because there are almost
    // always width 1, which means they don't tell you anything about spans.
    boolean verbose = false;
    int pruned = 0;
    List<Span> spans = new ArrayList<>();
    spans.add(Span.nullSpan);
    Sentence s = frames.getSentence();
    int n = s.size();
    for (int i = 0; i < n; i++) {
      for (int j = i + 1; j <= n; j++) {

        // TODO both of these pruning heuristics should go away when different
        // action modes (other than COMMIT) are added,
        // e.g. PRUNE_WIDER_THAN(k) and PRUNE_ENDING_IN_POS(s)
        int width = j - i;
        if (PRUNE_SPANS && width > 10) {
          pruned++;
          continue;
        }

        // there are certain POS that cannot end a span
        String lastPos = s.getPos(j - 1);
        if (PRUNE_SPANS &&
            ("CC".equals(lastPos)
            || "DT".equals(lastPos)
            || "IN".equals(lastPos)
            || "POS".equals(lastPos))) {
          // TODO test recall of this against actual arguments
          // TODO consider putting this into a pruning model
          pruned++;
          continue;
        }

        // TODO put a span pruning model here?
        spans.add(Span.getSpan(i, j));
      }
    }
    if (verbose) {
    LOG.warn("[naiveAllowableSpans] pruned " + pruned
        + " spans, REMOVE this and support a richer set of Actions!");
    }
    return spans;
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
    else
      return at.unapply(a, this);
  }

  public String show() {
    StringBuilder sb = new StringBuilder("State of " + frames.getId() + "\n");
    int T = committed.length;
    for (int t = 0; t < T; t++) {
      for (int k = 0; k < committed[t].length; k++) {
        Span a = committed[t][k];
        String as = a == null ? "NULL" : a.shortString();
        String gs = frames.getFrameInstance(t).getArgument(k).shortString();
        sb.append("(" + t + "," + k + ") = " + as + "\t" + gs + "\n");
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
