package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import org.apache.log4j.Logger;

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
  public static final Logger LOG = Logger.getLogger(State.class);

  protected FNTagging frames;
  private StateIndex stateIndex;
  private BitSet possible;
  private Span[][] committed; // TODO where to put? hopefully not in StateIndex

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
    for (int t = 0; t < T; t++) {
      int K = frames.getFrameInstance(t).getFrame().numRoles();
      committed[t] = new Span[K];
      for (int k = 0; k < K; k++)
        for (int i = 0; i < n; i++)
          for (int j = i + 1; j <= n; j++)
            possible.set(stateIndex.index(t, k, i, j), true);
    }
    return new State(frames, stateIndex, possible, committed);
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

  /**
   * Returns a span if one has been chosen for this role, or null otherwise.
   */
  public Span committed(int t, int k) {
    return committed[t][k];
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
        if (width > 10) {
          pruned++;
          continue;
        }

        // there are certain POS that cannot end a span
        String lastPos = s.getPos(j - 1);
        if ("CC".equals(lastPos)
            || "DT".equals(lastPos)
            || "IN".equals(lastPos)
            || "POS".equals(lastPos)) {
          // TODO test recall of this against actual arguments
          // TODO consider putting this into a pruning model
          pruned++;
          continue;
        }

        // TODO put a span pruning model here?
        spans.add(Span.getSpan(i, j));
      }
    }
    LOG.warn("[naiveAllowableSpans] pruned " + pruned
        + " spans, REMOVE this and support a richer set of Actions!");
    return spans;
  }

  public Frame getFrame(int t) {
    return frames.getFrameInstance(t).getFrame();
  }

  public int numFrameInstance() {
    return frames.numFrameInstances();
  }

  public FrameInstance getFrameInstance(int i) {
    return frames.getFrameInstance(i);
  }

  public Sentence getSentence() {
    return frames.getSentence();
  }

  /**
   * Returns the state resulting from applying the given action to this state.
   * Doesn't modify this state.
   */
  public State resultingFrom(Action a) {
    BitSet n = stateIndex.update(a, possible);
    int T = committed.length;
    Span[][] c = new Span[T][];
    for (int t = 0; t < T; t++)
      c[t] = Arrays.copyOf(committed[t], committed[t].length);
    return new State(frames, stateIndex, n, c);
  }

//  public boolean overlapsAnArg(int start, int end) {
//    Span s = Span.getSpan(start, end);
//    for (int t = 0; t < frames.numFrameInstances(); t++) {
//      Frame f = getFrameInstance(t).getFrame();
//      int K = f.numRoles();
//      for (int k = 0; k < K; k++) {
//        Span c = committed[t][k];
//        if (c != null && c != Span.nullSpan) {
//          if (s.overlaps(c) && !s.equals(c))
//            return true;
//        }
//      }
//    }
//    return false;
//  }

  // goes to the next state
//  public void apply(Action a) {
//    if (a.mode != Action.COMMIT)
//      throw new RuntimeException("implement me");
//
//    // NOTE: can't really do efficient unapply because a location may have been
//    // made illegal by some action other than the one you're trying to undo
//
//    if (a.aspan.isNullSpan()) {
//      committed[a.t][a.k] = Span.nullSpan;
//    } else {
//      committed[a.t][a.k] = a.aspan.getSpan();
//    }
//    int n = getSentence().size();
//    for (int i = 0; i < n; i++)
//      for (int j = i + 1; j <= n; j++)
//        if (possible[a.t][a.k][i][j])
//          numPossible--;
//    possible[a.t][a.k] = null;
//
//    // TODO precompute the set of spans that overlap with...
//    for (int i = 0; i < n; i++) {
//      for (int j = i + 1; j <= n; j++) {
//        if (overlapsAnArg(i, j)) {
//          for (int t = 0; t < frames.numFrameInstances(); t++) {
//            int K = getFrameInstance(t).getFrame().numRoles();
//            for (int k = 0; k < K; k++) {
//              if (committed[t][k] != null)
//                continue;
//              if (possible[t][k][i][j])
//                numPossible--;
//              possible[t][k][i][j] = false;
//            }
//          }
//        }
//      }
//    }
//
//    movesApplied++;
//  }

}
