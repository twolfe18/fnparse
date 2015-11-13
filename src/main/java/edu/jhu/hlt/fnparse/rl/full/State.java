package edu.jhu.hlt.fnparse.rl.full;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.role.span.FNParseSpanPruning;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;

public class State implements GeneratesActions {

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

  /**
   * Does not generate any actions because it doesn't hold onto a LL (actions
   * create nodes that are pushed onto the head of a LL).
   */
  public static final class RI {
    public final int k;   // k=-1 means only this span has been chosen, but it hasn't been labeled yet
    public final int q;
    public final Span s;

    // Instead of having a cache of FVs, just have a cache of RIs
    public IntDoubleUnsortedVector staticFeatures;
    public List<RI> successors;

    public RI(int k, int q, Span s) {
      assert k >= 0 || s != null
          : "don't create nodes for this, just generate an action for every (k,s) value";
      this.k = k;
      this.q = q;
      this.s = s;
    }
  }

  public static final class RILL {

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
    public final boolean noMoreArgRoles;

    public final fj.data.Set<Span> realizedSpans;

    // This is monad bind!
    public RILL(RI r, RILL l) {
      item = r;
      next = l;
      assert r.k < 64 && r.k >= 0;
      long rm = 1l << r.k;
      realizedRoles = l.realizedRoles | rm;
      realizedRolesCont = l.realizedRolesCont | (r.q == CONT ? rm : 0);
      realizedRolesRef = l.realizedRolesRef | (r.q == REF ? rm : 0);
      noMoreArgs = l.noMoreArgs || item == NO_MORE_ARGS;
      noMoreArgSpans = l.noMoreArgSpans || item == NO_MORE_ARG_SPANS;
      noMoreArgRoles = l.noMoreArgRoles || item == NO_MORE_ARG_ROLES;

      // TODO This needs clarification for the non-add-arg cases
      assert r.s != null && r.s != Span.nullSpan;
      realizedSpans = l.realizedSpans.insert(r.s);
    }

    public int getNumRealizedArgs() {
      return Long.bitCount(realizedRoles)
          + Long.bitCount(realizedRolesCont)
          + Long.bitCount(realizedRolesRef);
    }

  }

  public static final RI NO_MORE_ARGS = new RI(-1, -1, null);
  public static final RI NO_MORE_ARG_SPANS = new RI(-2, -2, null);
  public static final RI NO_MORE_ARG_ROLES = new RI(-3, -3, null);

  public final class FI implements GeneratesActions {

    public final Frame f; // f==null means only this target has been selected, but it hasn't been labled yet
    public final Span t;  // Currently must be non-null. You could imagine assuming a frame exists THEN finding its extend (...AMR) but thats not what we're currently doing
    public final RILL args;

    public FI(FI copy, RILL args) {
      this(copy.f, copy.t, args);
    }

    public FI(Frame f, Span t, RILL args) {
      this.f = f;
      this.t = t;
      this.args = args;
    }

    public FI withArg(RI arg) {
      return new FI(this, new RILL(arg, args));
    }

    public boolean isFrozen() {
      return args.item == NO_MORE_ARGS;
    }

    @Override
    public double partialUpperBound() {
      throw new RuntimeException("implement me");
    }

    /**
     * - NO_MORE_ARGS
     * - NO_MORE_ARG_SPANS
     * - NO_MORE_ARG_ROLES
     * - new (k,?) nodes
     * - new (?,k) nodes
     * - new (?,?) nodes      => Don't actually create nodes like this, just create one action per (k,s)
     */
    @Override
    public void next(Beam beam, double prefixScore) {

      if (args.noMoreArgs)
        return;

      // TODO NO_MORE_ARGS
      RILL rill = new RILL(NO_MORE_ARGS, args);
      FI fi = new FI(this, rill);
      FILL howToGetOldFILL = ???;
      beam.offer(new State(new FILL(fi, howToGetOldFILL)), ZERO);

      // TODO NO_MORE_ARG_SPANS

      // TODO NO_MORE_ARG_ROLES

      for (RILL cur = args; cur != null; cur = cur.next) {
        RI ri = cur.item;

        if (ri.k < 0 && ri.s != null) {
          // loop over roles
          if (args.noMoreArgRoles) {
            // deltaLoss should have accounted for the cost of missing any
            // possible items stemming from open (?,s) nodes.
            continue;
          }

        } else if (ri.k >= 0 && ri.s == null) {
          // loop over spans
          if (args.noMoreArgSpans) {
            // deltaLoss should have accounted for the cost of missing any
            // possible items stemming from open (k,?) nodes.
            continue;
          }

        } else {
          assert ri.k >= 0 && ri.s != null;
        }

        if (config.roleByRole)
          break;
      }
    }

  }

  // Note NO_MORE_FRAMES really means "no more FIs".
  // By analogy to RILL and NO_MORE_ROLES, there is no true "no more frames
  // (irrespective of t) in this sentence" action because it doesn't really
  // make sense.
  public static final FI NO_MORE_FRAMES = new State(null).new FI(null, null, null);
  public static final FI NO_MORE_TARGETS = new State(null).new FI(null, null, null);

  // Q: How to tell apart:
  // 1) prepend new (t,f)
  // 2) replace newFrame (which contains a new role) with the corresponding (t,f) node in frames
  // A: case 1 has newFrame.args==null and case 2 has newFrame.args!=null :)
  public static final class FILL {

    public final FI item;
    public final FILL next;

    // Caches which should be updateable in O(1) which may inform/prune next() or global features
    public final int numFrameInstances;
    // TODO add "last FI which is not STOP/FULL"? -- skip list of non-full FIs?

    public final fj.data.Set<Span> targetsSelectedSoFar;

    public final boolean noMoreFrames;
    public final boolean noMoreTargets;

    /**
     * New target chosen (next actions will range over features)
     */
    public FILL(FI highlightedTarget, FILL otherFrames) {
      item = highlightedTarget;
      next = otherFrames;
      numFrameInstances = otherFrames.numFrameInstances + 1;
      targetsSelectedSoFar = highlightedTarget.t != null
          ? otherFrames.targetsSelectedSoFar.insert(highlightedTarget.t)
          : otherFrames.targetsSelectedSoFar;
      noMoreFrames = otherFrames.noMoreFrames || highlightedTarget == NO_MORE_FRAMES;
      noMoreTargets = otherFrames.noMoreTargets || highlightedTarget == NO_MORE_TARGETS;
    }

    // NOTE: This is gone because we cannot allow FILL to create its own FI
    // nodes, which are non-static (this is a static class). More natural for
    // This just to represent cons (LL) plus any monoid ops like numFrameInstances or targetsUsed
//    /**
//     * A new arg has been added to an existing frame, somewhere in allFrames.
//     * Find it and make a new FI node to put at the head.
//     */
//    public FILL(FILL addArgTo, RI argToAdd, FILL allFrames) {
//      FI f = addArgTo.item;
//      assert f != NO_MORE_FRAMES;
//
//      // Nodes at the front of allFrames (need to be copied due to mutation
//      // down in the list). If addArgTo == allFrames, then this is O(1), and
//      // gets progressively more costly addArgTo is deeper in the allFrames list
//      List<FI> head = new ArrayList<>();
//      for (FILL cur = allFrames; cur != addArgTo; cur = cur.next)
//        head.add(cur.item);
//
//      // Build FI with one new argument
//      RILL newRoles = new RILL(argToAdd, f.args);
//      item = new FI(f.f, f.t, newRoles);
//      numFrameInstances = addArgTo.numFrameInstances;
//
//      // Glue together:
//      // a) the tail of allFrames (from addArgTo on)
//      FILL nextMut = addArgTo.next;
//      // b) the frames that came before addArgTo
//      for (int i = head.size() - 1; i >= 0; i--) {
//        // Frames didn't change, the LL did
//        // Make new LL nodes, not new FI nodes
//        FI fi = head.get(i);
//        nextMut = new FILL(fi, nextMut);
//      }
//      next = nextMut;
//    }

    /*
     * Only non-LL nodes create actions.
     * TODO Move stuff to State or FI
    @Override
    public void next(Beam beam, double prefixScore) {

      assert item.t != null;
      if (item.f == null) {
        // Label this target
        // (t,) -> (t,f)
        // Note that this needs to be here because the only way to accomplish
        // this is to add a new FI (to a FILL). FI doesn't know about FILL,
        // so it can't add to this LL.

      } else {

        // Step 2/2 actions
        boolean step2 = false;
        for (RILL cur = item.args;
            cur != null && cur.item != NO_MORE_ARGS;
            cur = cur.next) {
          int k = cur.item.k;   // TODO q
          Span s = cur.item.s;

          if (k >= 0 && s == null) {
            // (k,?) actions, Step 2/2
            step2 = true;
          } else if (k < 0 && s != null) {
            // (s,?) actions, Step 2/2
            step2 = true;
          } else if (k < 0 && s == null) {
            // (?,?) actions, Step 2/2
            step2 = true;
          }

          if (step2 && config.roleByRole) {
            // This means we work one RILL at a time, thus there must not be
            // any possible actions out of (cdr RILL), which gives us a nice
            // speedup.
            break;
          }
        }

        if (!step2) {
          // (k,?) actions, Step 1/2
          if (config.chooseArgRoleFirst) {
            long realizedRolesMask = item.args.realizedRoles;
            int K = item.f.numRoles();
            for (int k = 0; k < K; k++) {
              long ki = 1l << k;
              if ((realizedRolesMask & ki) == 0) {
                // TODO Generate action for adding (k,?)
              } else {
                // TODO continuation/reference roles?
              }
            }
          }

          // (?,s) actions, Step 1/2
          if (config.chooseArgSpanFirst) {
            fj.data.Set<Span> realizedSpans = item.args.realizedSpans;
            int t = -1;   // TODO old code assumes t:int, now t:Span
            for (Span s : prunedSpans.getPossibleArgs(t)) {
              if (!realizedSpans.member(s)) {
                // TODO Generate action for adding (?,s)
              }
            }
          }

          // (k,s) actions (SLOW: high branching factor)
          if (config.chooseArgOneStep) {
            throw new RuntimeException("implement me");
          }
        }
      }

      // Recurse on (cdr FILL)
      if (!config.frameByFrame) {
        // To encourage the model to make cheap updates, add a slight penalty
        // to actions further down the FILL.
        double lenPenalty = 0.1;
        next.next(beam, prefixScore - lenPenalty);
      }
    }
     */

        // Escaping early!
//        Double lb, ub;
//        if ((lb = b.lowerBound()) != null
//            && (ub = fiModel.scoreUpperBound()) != null
//            && lb > lengthPenaltySum + ub) {
//          break;
//        }
//        if (b.lowerBound() > prefixScore + -lengthPenaltySum)
        /*
         * The hardest thing to prune are the things deep in the tree.
         * These are the only things you want to prune, since making a new state isn't that expensive and the beam will immediately reject it (no cascade of un-necessary work)
         * 
         * => prefix score is a necessary way to communicate down the tree
         * => a way to communicate upper bounds *up the tree* is still needed.
         */
//        if (beam.lowerBound() > prefixScore + who)
//          break;
        // Who am I going to call next on?
        // aka what is my subtree?
        // I need to ask *that subtree* what its bound is.
        // FILL_1 -> FILL_2 -> FILL_2
        // What is the upper bound of FILL_2?
        // Is it the same as the bound on FILL_1?
        // => I think I'm close enough to having an implementation that works *without* pruning, so I'm going to get that working
        //    After this is done, it may be easier to see the answer to who to ask for the upper bound.
  }

  private Sentence sentence;
  private FNParse label;      // may be null

  // State space pruning
  private FNParseSpanPruning prunedSpans;     // or Map<Span, List<Span>> prunedSpans
  private Map<Span, List<Frame>> prunedFIs;    // TODO fill this in

  // Represents z
  private FILL frames;

  private IntDoubleUnsortedVector[][][] staticFeatures;   // [t,i,j] == (t,s)
  private Config config;

//  private double score;
//  private double loss;

  public Model<String> tfksModel;
  public Model<String> tfsModel;
  public Model<String> tfkModel;
  public Model<String> tfModel;
  public Model<String> tModel;

  /*
   * AH, you need to upper bound the actions that could be generated from under a given node (e.g. FILL can generate actions from g3)
   * The saving grace is that the more g3 actions you evaluate, the better the chance that you'll find some high-scoring next states to push up the lower bound of you beam.
   * 
   * How do we get the upper bound on a score that may come from any action in a subset/subtree?
   * Doing it backwards doesn't make sense: e.g. fillModel = fillModel + fiModel + rillModel + riModel
   * (though this is the semantics you need for upper bounding actions)
   * AH, you have a model where you pass down the prefix to the upper bound:
   *
   * => next() must be given a prefix score and a prefix score upper bound
   */

  public State(FILL frames) {
    this.frames = frames;
  }

  /**
   * - NO_MORE_FRAMES
   * - NO_MORE_TARGETS
   * - (t,), which adds new FIs to the head of args:FILL
   * - (t,) -> (t,f), which adds new FIs to the head of args:FILL
   * 
   * NOTE: The reason that We need State is that there are some actions which
   * are only allowed at the head of a FILL (i.e. not relevant to each FI) such
   * as NO_MORE_FRAMES.
   * NOTE: Another reason is that for *mutation* (see below) to work, you need a
   * need handle on the head of the list.
   *
   * The only reason I needed the "copy LL nodes up to a point" was due to the
   * fact that FI has a RILL, prepending to that RILL is tantamount to mutation
   * of a FI and a FILL. Mutation => Copying.
   *
   * Given that we are only mutating items in FILL and not in RILL, then perhaps
   * the rule of non-LL nodes generate actions does not apply to FI/RILL.
   * If RILL generated actions, it would generate:
   * - (k,?) -> new RI for (k,s)
   * - (?,k) -> new RI for (k,s)
   * - (?,?) -> new RI for (k,s)
   * This action would still need a pointer to the head of the list to perform
   * the cons required by action/apply.
   * BUT, we could say that RILL asks the RIs to give "potential new RI nodes" (cache).
   * RILL generates the actions (implements next(), but RIs cache RIs they could lead to.
   * => Lets implement RI caching later
   *    Still need to figure out how to cache static features (RI) and not dynamic features (RILL)
   *
   * One last reason supporting the "non-LL nodes generate actoins" rule is that
   * only the non-LL nodes have the proper/total information needed for global
   * features. For example, numArgs. A RILL may know how many args have been
   * added *before* it (in (cdr RILL)), but it doesn't know how many have been
   * added in total.
   */

  /**
   * Clarity:
   * State == new FI nodes
   * - NO_MORE_FRAMES
   * - NO_MORE_TARGETS
   * - new (t,?) nodes
   * - new (t,f) nodes
   * FI == new RI nodes
   * - NO_MORE_ARGS
   * - NO_MORE_ARG_SPANS
   * - NO_MORE_ARG_ROLES
   * - new (k,?) nodes
   * - new (?,k) nodes
   * - new (?,?) nodes      => Don't actually create nodes like this, just create one action per (k,s)
   *
   * => NO. Not right. One step out of State needed. See below.
   */


  // Work out scoring after the transition system is working
  public static final Adjoints ZERO = null;

  /**
   * Replaces the node tail.item with newFrame (in the list this.frames).
   * O(1) if tail == this.frames and O(T) otherwise.
   */
  public State surgery(FILL tail, FI newFrame) {
    // Pick off the states between this.frames and tail
    List<FI> copy = new ArrayList<>();
    for (FILL c = this.frames; c != tail; c = c.next) {
      assert c != null : "didn't find tail in this.frames?";
      copy.add(c.item);
    }

    // Construct the new FILL
    FILL newFILL = new FILL(newFrame, tail);

    // Prepend all the copied nodes between this.frame and tail
    for (int i = copy.size() - 1; i >= 0; i--)
      newFILL = new FILL(copy.get(i), newFILL);

    return new State(newFILL);
  }

  // Sugar
  public static State scons(FI car, FILL cdr) {
    return new State(cons(car, cdr));
  }

  // Sugar
  public static FILL cons(FI car, FILL cdr) {
    return new FILL(car, cdr);
  }

  // Sugar
  public static RILL cons(RI car, RILL cdr) {
    return new RILL(car, cdr);
  }

  /**
   * Ok, screw it, I'm going to do all of the actions out of this one method.
   * The reason that I did this was that:
   * adding an RI -> need to mutate FI -> need to replace node in FILL -> need State
   */
  @Override
  public void next(Beam beam, double prefixScore) {

    if (frames.noMoreFrames)
      return;
    beam.offer(new State(new FILL(NO_MORE_FRAMES, frames)), ZERO);
    if (!frames.noMoreTargets)
      beam.offer(new State(new FILL(NO_MORE_TARGETS, frames)), ZERO);

    /* New FI actions *********************************************************/
    // Frames Step 1/2
    // TODO Need to check that (Frames Step 2/2) doesn't need to be run first.
    fj.data.Set<Span> tsf = frames.targetsSelectedSoFar;
    for (Span t : prunedFIs.keySet()) {
      if (!config.oneFramePerSpan || !tsf.member(t)) {
        // (t,)
        RILL args = null;
        FI fi = new FI(null, t, args);
        beam.offer(new State(new FILL(fi, frames)), ZERO);

        // (t,f)
        if (config.chooseFramesOnStep) {
          for (Frame f : prunedFIs.get(t)) {
            RILL args2 = null;
            FI fi2 = new FI(f, t, args2);
            beam.offer(new State(new FILL(fi2, frames)), ZERO);
          }
        }
      }
    }

    /* New RI actions *********************************************************/
    for (FILL cur = frames; cur != null; cur = cur.next) {

      FI fi = cur.item;
      assert fi.t != null;

      if (fi.args.noMoreArgs)
        continue;

      // Frames Step 2/2
      if (fi.f == null) {
        // Loop over t
        continue;
      }

      // Args Step 2/2
      // (Takes precedence over Step 1/2)
      boolean haveToCompleteAnArgForThisFrame = false;
      for (RILL arg = fi.args; arg != null; arg = arg.next) {
        RI ri = arg.item;

        if (ri.k < 0 && ri.s != null) {
          // loop over roles
          if (arg.noMoreArgRoles) {
            // deltaLoss should have accounted for the cost of missing any
            // possible items stemming from open (?,s) nodes.
            continue;
          }
          haveToCompleteAnArgForThisFrame = true;

        } else if (ri.k >= 0 && ri.s == null) {
          // loop over spans
          if (arg.noMoreArgSpans) {
            // deltaLoss should have accounted for the cost of missing any
            // possible items stemming from open (k,?) nodes.
            continue;
          }
          haveToCompleteAnArgForThisFrame = true;

        } else {
          assert ri.k >= 0 && ri.s != null;
        }

        if (config.roleByRole)
          break;
      } // END ARG LOOP


      if (haveToCompleteAnArgForThisFrame) {
        // Args Step 1/2
        if (config.chooseArgRoleFirst) {
          // Loop over k
          int K = fi.f.numRoles();
          for (int k = 0; k < K; k++) {
            RI newArg = new RI(k, -1, null);
            beam.offer(this.surgery(cur, fi.withArg(newArg)), ZERO);
          }
        }
        if (config.chooseArgSpanFirst) {
          // Loop over s
          int t = -1; // TODO have t:Span need t:int
          for (Span s : prunedSpans.getPossibleArgs(t)) {
            RI newArg = new RI(-1, -1, s);
            beam.offer(this.surgery(cur, fi.withArg(newArg)), ZERO);
          }
        }
        if (config.chooseArgOneStep) {
          // Loop over (k,s)
          int K = fi.f.numRoles();
          int t = -1; // TODO have t:Span need t:int
          for (Span s : prunedSpans.getPossibleArgs(t)) {
            for (int k = 0; k < K; k++) {
              RI newArg = new RI(k, -1, s);
              beam.offer(this.surgery(cur, fi.withArg(newArg)), ZERO);
            }
          }
        }

        // NO_MORE_ARGS
        beam.offer(this.surgery(cur, fi.withArg(NO_MORE_ARGS)), ZERO);

        // NO_MORE_ARG_SPANS
        beam.offer(this.surgery(cur, fi.withArg(NO_MORE_ARG_SPANS)), ZERO);

        // NO_MORE_ARG_ROLES
        beam.offer(this.surgery(cur, fi.withArg(NO_MORE_ARG_ROLES)), ZERO);
      }

      if (config.frameByFrame)
        break;
    } // END FRAME LOOP

  }

  @Override
  public double partialUpperBound() {
    return tModel.scoreUpperBound();
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


  public interface Model<X> {
    public Adjoints score(X x);
    public double scoreUpperBound();  // return an upper bound on what score(x).forwards() could be (forall x), or -Infinity if one is not known
  }
}
