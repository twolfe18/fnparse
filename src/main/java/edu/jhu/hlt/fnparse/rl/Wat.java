package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.role.span.FNParseSpanPruning;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;

public class Wat {

  // Values for q
  public static final int BASE = 0;
  public static final int CONT = 1;
  public static final int REF = 2;

  public static class RI {
    public final int k;   // k=-1 means only this span has been chosen, but it hasn't been labeled yet
    public final int q;
    public final Span s;
    public RI(int k, int q, Span s) {
      this.k = k;
      this.q = q;
      this.s = s;
    }
  }

  public static class RILL {
    public final RI item;
    public final RILL next;

    // Caches which should be updateable in O(1) which may inform/prune next() or global features
    public final long realizedRoles;  // does 64 roles play when you have to deal with C/R roles?
    public final long realizedRolesCont;
    public final long realizedRolesRef;
//    public int numRealizedArgs;   // probably better to just do countOneBits(realizedRoles)
//    public BitSet realizedSpans;  // probably better left to just traversing the list
    // NOTE: If you had a dependency tree, you could use a token index to represent a span/argLocation... limiting to length 64 sentences might be a problem though...

    // I could keep a log(n) time index on realizedSpans by keeping a balanced
    // tree of nodes sorted by spans.

    // This is monad bind!
    public RILL(RI r, RILL l) {
      item = r;
      next = l;
      assert r.k < 64;
      long rm = 1l << r.k;
      realizedRoles = l.realizedRoles | rm;
      realizedRolesCont = l.realizedRolesCont | (r.q == CONT ? rm : 0);
      realizedRolesRef = l.realizedRolesRef | (r.q == REF ? rm : 0);
    }

    public int getNumRealizedArgs() {
      return Long.bitCount(realizedRoles)
          + Long.bitCount(realizedRolesCont)
          + Long.bitCount(realizedRolesRef);
    }

    public List<String> next() {
      if (item.s == null && item.k >= 0) {
        return Arrays.asList("find a span for role k");
      } else if (item.s != null && item.k < 0) {
        return Arrays.asList("find a role for span s");
      } else if (item.s != null && item.k >= 0) {
        // This RILL has already been fixed, TODO could have FI skip this?
        return Collections.emptyList();
      } else {
        assert item.s == null && item.k < 0;
        throw new IllegalStateException("must specify either k or s");
      }
    }
  }

  // Put this at the head of a RILL to signify that no more args may be assigned to this FI
  public static final RI NO_MORE_ARGS = new RI(-1, -1, null);

  public static class FI {
    public final Frame f;           // f==null means only this target has been selected, but it hasn't been labled yet
    public final Span t;
    public final RILL args;

    public FI(Frame f, Span t, RILL args) {
      this.f = f;
      this.t = t;
      this.args = args;
    }

    public boolean isFrozen() {
      return args.item == NO_MORE_ARGS;
    }
  }

  // Put this at the head of a FILL to signify that no more frames appear in this sentence
  public static final FI NO_MORE_FRAMES = new FI(null, null, null);

  // Q: How to tell apart:
  // 1) prepend new (t,f)
  // 2) replace newFrame (which contains a new role) with the corresponding (t,f) node in frames
  // A: case 1 has newFrame.args==null and case 2 has newFrame.args!=null :)
  public static class FILL {    // aka "state"
    public final FI item;
    public final FILL next;

    // Caches which should be updateable in O(1) which may inform/prune next() or global features
    public final int numFrameInstances;
    // TODO add "last FI which is not STOP/FULL"? -- skip list of non-full FIs?

    /**
     * New target chosen (next actions will range over features)
     */
    public FILL(FI highlightedTarget, FILL otherFrames) {
      // This is monad bind!
      item = highlightedTarget;
      next = otherFrames;
      numFrameInstances = otherFrames.numFrameInstances + 1;
    }

    /**
     * A new arg has been added to an existing frame, somewhere in allFrames.
     * Find it and make a new FI node to put at the head.
     */
    public FILL(FILL addArgTo, RI argToAdd, FILL allFrames) {
      // This is monad bind!
      FI f = addArgTo.item;

      // Nodes at the front of allFrames (need to be copied due to mutation
      // down in the list). If addArgTo == allFrames, then this is O(1), and
      // gets progressively more costly addArgTo is deeper in the allFrames list
      List<FI> head = new ArrayList<>();
      for (FILL cur = allFrames; cur != addArgTo; cur = cur.next)
        head.add(cur.item);

      // Build FI with one new argument
      RILL newRoles = new RILL(argToAdd, f.args);
      item = new FI(f.f, f.t, newRoles);
      numFrameInstances = addArgTo.numFrameInstances;

      // Glue together:
      // a) the tail of allFrames (from addArgTo on)
      FILL nextMut = addArgTo.next;
      // b) the frames that came before addArgTo
      for (int i = head.size() - 1; i >= 0; i--) {
        // Frames didn't change, the LL did
        // Make new LL nodes, not new FI nodes
        FI fi = head.get(i);
        nextMut = new FILL(fi, nextMut);
      }
      next = nextMut;
    }

    public List<String> next() {
      // NOTE, this could be done recursively as:
      // flatMap(next, item.args) ++ next.next()
      // TODO Convert into recursive form so that we can cache actions in FILL

      List<String> all = new ArrayList<>();
      if (item != NO_MORE_FRAMES) {
        all.add("loop over targets");
      }
      for (FILL cur = this; cur != null; cur = cur.next) {
        if (cur.item.isFrozen()) {
          // We've settled on all of the args for this frame
          continue;
        }
        if (cur.item.args == null) {
          // We've chosen a target but need to assign a frame to it
          assert cur.item.f == null;
          all.add("loop over frames");
        } else {
          // Assign roles to the given frame
          for (RILL curr = item.args; curr != null; curr = curr.next) {
            // Each RI may have either:
            // a) k selected: loop over spans
            // b) s selected: loop over roles
            all.addAll(curr.next());
          }
          all.add("STOP cur.item");
          all.add("loop over remaining spans");   // probably only want to do one of these
          all.add("loop over remaining roles");
        }
      }
      return all;
    }
  }

  public static class State {
    private Sentence sentence;
    private FNParse label;      // may be null
//    private Map<Span, List<Span>> prunedSpans;
    private FNParseSpanPruning prunedSpans;
    private FILL frames;
    private IntDoubleUnsortedVector[][][] staticFeatures;
    private double score;
    private double loss;

    public State(FILL frames) {
      this.frames = frames;
    }
  }

  public static class Action {
    // What are the types of actions I need?
    // 1) add target (no frame specified)
    // 2) assign frame to an existing target
    // 3a) choose a span/role given a particular (t,f) => choose a role
    // 3a) choose a role/span given a particular (t,f) => choose a span
  }

  // (,) -> (t,)
  // (t,) -> (t,f)
  // (t,f) -> (t,f,s) -> (t,f,s,k)
  // (t,f) -> (t,f,k) -> (t,f,k,s)

  // f(t,s)
  // f(t,f,s)
  // f(t,f,k)? -- this isn't terribly natural, ..except for dynamic/state features!

  // First lets answer the question of if (3a) and (3b) style sequences can co-exist.
  // How would a problem arise?
  // If a given (t,k) had a (s,?) and (k,?) set of actions, and we could take more than one at the same time?
  // If we let both of those expand, they would have one overlapping action... not particularly a bad thing
  // If we allow either a (s,?) or a (k,?) to be added to a RILL, and force it to be immediately resolved, then an (s,k) will be chosen and it will prevent further (s,?) or (k,?) actions

  // dynamic features for (t,f,k)?
  // - RoleCooc
  // - NumArgs
  // + Useful for stopping quickly: "I can rule out ARG4 because I already have 3 args..."
  // dynamic features for (t,f,s)?
  // - RoleLoc
  // - NumArgs
  // + WAY more static features

  // I think I'm settling on:
  // - Do NOT allow (s,k) expansion, even for one (t,f), only allow (s,?) or (k,?)
  // - Do allow for both (s,?) and (k,?) expansions. They both have their merit.
}
