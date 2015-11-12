package edu.jhu.hlt.fnparse.rl.full;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.role.span.FNParseSpanPruning;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;

public class State {

  /*
   * The one thing that I thought about but haven't added is how to do state merging.
   * Two states are the same if the set index(z[t,f,k,s] == 1) is the same.
   * I may derive these sets in different orders
   *   (assuming all of my actions -- or pairs of actions) fill in a z[t,f,k,s]=1)
   * There are N! strings representing a set of size N.
   * We can represent ignorance of these N! ways by talking about strings composed out of an associative and communative operator
   * Integer multiplication is a good example!
   * If we represent each (t,f,k,s) with a unique prime, then the product of these is the name of that set.
   * We can reduce the problem a bit to "find the first Z primes" + "index (t,f,k,s) into [1..Z]"
   * Lets use capital letters to describe bounds, e.g. 0 <= t <= T.
   * Assuming T=20 F=1000 K=30 S=100, Z=60M
   *   60M ints is 240MB...
   * The 50M-th prime is 982,451,653 = exp_2(29.87)
   *   https://primes.utm.edu/lists/small/millions/
   * Realistically we will have to represent sets upwards of 10 items, meaning we're going to need at least 300 bits, and a lot of math.
   * What if we just take h(i) = primes[i % bigNumber], where i = index(t,f,k,s), and only store bigNumber primes?
   * The probability of any collision, h(i) == h(i') where i != i' is p(c) = 0 * 1/bigNumber + (bigNumber-1)/bigNumber * 1/bigNumber
   *   where the first prob is p(i and i' fall into the same bucket of size bigNumber) and the second is p(collision | same bucket)
   * The probability of no collisions in a 50 item set is 1 - (1-p(c))^50
   * Using the approximation p(c) = 1/bigNumber and bigNumber=10k
   * p(no collision with 50 items) = 1 - (1-1/bigNumber)^50 = 0.00499, less than 1%!
   * StateIndex can handle (t,f,k,s) => [1..Z]
   * Just get the first 10k primes or so.
   * Every RI which is fully fleshed out knows (t,f,k,s), so it has a prime.
   * Every RILL can store the product over all RI (for RI nodes that are like (s,?) or (k,?), just make this "prime" to be 1)
   * Every FILL can store the product over all FI.args : RILL
   * Every time you make a new state, you put it into a DP hash table with the key being this product of primes.
   * If you add and get a true collision (on the prod prime, not a hash-bucket collision), take the one with the higher objective.
   */

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

    public final boolean noMoreArgs;
    public final boolean noMoreArgSpans;

    // This is monad bind!
    public RILL(RI r, RILL l) {
      item = r;
      next = l;
      assert r.k < 64;
      long rm = 1l << r.k;
      realizedRoles = l.realizedRoles | rm;
      realizedRolesCont = l.realizedRolesCont | (r.q == CONT ? rm : 0);
      realizedRolesRef = l.realizedRolesRef | (r.q == REF ? rm : 0);
      noMoreArgs = l.noMoreArgs || item == NO_MORE_ARGS;
      noMoreArgSpans = l.noMoreArgSpans || item == NO_MORE_ARG_SPANS;
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

  // See chooseAllArgSpansFirst
  public static final RI NO_MORE_ARG_SPANS = new RI(-2, -2, null);

  public class FI {
    public final Frame f;           // f==null means only this target has been selected, but it hasn't been labled yet
    public final Span t;
    public final RILL args;

    // Every time a role is added to this frame, a new FI is created,
    // thus it is safe to cache actions.
    private List<String> actionCache;

    public FI(Frame f, Span t, RILL args) {
      this.f = f;
      this.t = t;
      this.args = args;
    }

    public boolean isFrozen() {
      return args.item == NO_MORE_ARGS;
    }

    public List<String> next() {
      if (actionCache != null)
        return actionCache;
      actionCache = new ArrayList<>();

      if (args.noMoreArgs) {
        return actionCache;
      }
      if (args.noMoreArgSpans) {
        // Disallow (s,?) actions
      }

      // Add NO_MORE_ARGS action
      // Add NO_MORE_ARG_SPANS action

      /* Step 2 of 2-step actions */
      RI ri = args.item;
      if (ri.k < 0 && ri.s != null) {
        // Loop over k
        return actionCache;
      } else if (ri.k >= 0 && ri.s == null) {
        // Loop over s
        return actionCache;
      }

      if (config.chooseArgOneStep) {
        /* One step actions */
        // Loop over (k,s)
        return actionCache;
      } else {
        /* Step 1 of 2-step actions */
        if (config.chooseArgRoleFirst) {
          // Loop over k
        }
        if (config.chooseArgSpanFirst) {
          // Loop over s
        }
        return actionCache;
      }
    }
  }

  // Put this at the head of a FILL to signify that no more frames appear in this sentence
  public static final FI NO_MORE_FRAMES = new State(null).new FI(null, null, null);

  // Q: How to tell apart:
  // 1) prepend new (t,f)
  // 2) replace newFrame (which contains a new role) with the corresponding (t,f) node in frames
  // A: case 1 has newFrame.args==null and case 2 has newFrame.args!=null :)
  public class FILL {    // aka "state"
    public final FI item;
    public final FILL next;

    // Caches which should be updateable in O(1) which may inform/prune next() or global features
    public final int numFrameInstances;
    // TODO add "last FI which is not STOP/FULL"? -- skip list of non-full FIs?

    // I'm not allowing f to precede t in an action sequence, so we only need to check if we're done with (t,f)s
    public final boolean noMoreFrames;

    /**
     * Put an end to any more frames/targets being predicted in this FILL.
     * (Note, you can still add args even if you can't add frames)
     */
    public FILL(FILL preventAddingFramesTo) {
      noMoreFrames = true;
      next = preventAddingFramesTo;
      item = NO_MORE_FRAMES;
      numFrameInstances = preventAddingFramesTo.numFrameInstances;
    }

    /**
     * New target chosen (next actions will range over features)
     */
    public FILL(FI highlightedTarget, FILL otherFrames) {
      item = highlightedTarget;
      next = otherFrames;
      numFrameInstances = otherFrames.numFrameInstances + 1;
      assert highlightedTarget != NO_MORE_FRAMES;
      assert !otherFrames.noMoreFrames;
      noMoreFrames = false;
    }

    /**
     * A new arg has been added to an existing frame, somewhere in allFrames.
     * Find it and make a new FI node to put at the head.
     */
    public FILL(FILL addArgTo, RI argToAdd, FILL allFrames) {
      FI f = addArgTo.item;
      assert f != NO_MORE_FRAMES;
      assert !allFrames.noMoreFrames;
      noMoreFrames = false;

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
      if (noMoreFrames) {
        // Only check for new roles
      } else {
        // Loop over targets
        // For each FILL s.t. f==null, loop over frames
        // Add NO_MORE_FRAMES action
        if (config.frameByFrame) {
          // ???
        }
      }

      // TODO Need to check that we're not adding a target for a FI that
      // we've already built (one frame per target)
      all.add("loop over targets");

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

        if (config.frameByFrame && all.size() > 0)
          break;
      }
      return all;
    }
  }

  private Sentence sentence;
  private FNParse label;      // may be null
  private FNParseSpanPruning prunedSpans;     // or Map<Span, List<Span>> prunedSpans
  private FILL frames;
  private IntDoubleUnsortedVector[][][] staticFeatures;   // [t,i,j] == (t,s)
  private Config config;
  private double score;
  private double loss;

  public State(FILL frames) {
    this.frames = frames;
  }

  public List<String> next() {
    List<String> all = new ArrayList<>();
    all.add("loop over possible targets that hanven't been selected yet");
    all.addAll(frames.next());
    return all;
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



  // In general:
  // It is better to put next() in FI/RI because they are cached more
  // HOWEVER, some actions depend on the entire list, things like NO_MORE_ARGS
  // \t.(t,) -> FILL
  // \f.(t,f) -> FI
  // \s.(t,f,s) -> RILL
  // \k.(t,f,k) -> RILL
  // \s.(t,f,k,s) -> RI
  // where \x means loop over x, slight bastardization of lambda notation
}
