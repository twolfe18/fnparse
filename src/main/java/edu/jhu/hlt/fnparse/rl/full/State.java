package edu.jhu.hlt.fnparse.rl.full;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.inference.role.span.FNParseSpanPruning;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
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

  /**
   * Does not generate any actions because it doesn't hold onto a LL (actions
   * create nodes that are pushed onto the head of a LL).
   */
  public static final class RI {
    public final int k;   // k=-1 means only this span has been chosen, but it hasn't been labeled yet
    public final int q;
    public final Span s;

    // Instead of having a cache of FVs, just have a cache of RIs
//    public IntDoubleUnsortedVector staticFeatures;
//    public List<RI> successors;

    public RI(int k, int q, Span s) {
      assert k >= 0 || s != null
          : "don't create nodes for this, just generate an action for every (k,s) value";
      this.k = k;
      this.q = q;
      this.s = s;
    }
  }

  public static Adjoints sum(Adjoints... adjoints) {
    throw new RuntimeException("implement me");
  }

  public final class RILL {

    public final RI item;
    public final RILL next;

    // Caches which should be updateable in O(1) which may inform/prune next() or global features
    public final long realizedRoles;  // does 64 roles play when you have to deal with C/R roles?
    public final long realizedRolesCont;
    public final long realizedRolesRef;
//    public int numRealizedArgs;   // probably better to just do countOneBits(realizedRoles)
//    public BitSet realizedSpans;  // probably better left to just traversing the list
    // NOTE: If you had a dependency tree, you could use a token index to represent a span/argLocation... limiting to length 64 sentences might be a problem though...

    public final boolean noMoreArgs;
    public final boolean noMoreArgSpans;
    public final boolean noMoreArgRoles;

    public final fj.data.Set<Span> realizedSpans;

    // Next RI which is either (k,?) or (?,s)
    // TODO This probably makes more sense as a RILL
    public final RI incomplete; // can be null

    // Sum of static features for all RI in this list
    public final Adjoints staticFeatures;

    /**
     * @param f is needed in order to lookup features.
     * @param r is the new RI being prepended.
     * @param l is the list being prepeded to.
     */
    public RILL(FI f, RI r, RILL l) {
      item = r;
      next = l;
      final long rm;
      if (r.k >= 0) {
        assert r.k < 64;
        rm = 1l << r.k;
      } else {
        rm = 0;
      }
      realizedRoles = l.realizedRoles | rm;
      realizedRolesCont = l.realizedRolesCont | (r.q == CONT ? rm : 0);
      realizedRolesRef = l.realizedRolesRef | (r.q == REF ? rm : 0);
      noMoreArgs = l.noMoreArgs || item == NO_MORE_ARGS;
      noMoreArgSpans = l.noMoreArgSpans || item == NO_MORE_ARG_SPANS;
      noMoreArgRoles = l.noMoreArgRoles || item == NO_MORE_ARG_ROLES;

      realizedSpans = r.s == null ? l.realizedSpans : l.realizedSpans.insert(r.s);

      if (l.incomplete != null) {
        if (completes(r, l.incomplete)) {
          incomplete = null;
        } else {
          assert !incomplete(r) : "only can have one incomplete node?";
          incomplete = l.incomplete;
        }
      } else {
        if (incomplete(r))
          incomplete = r;
        else
          incomplete = null;
      }

      staticFeatures = sum(
          l.staticFeatures,
          State.this.staticFeatureCache.scoreTFKS(f.t, f.f, r.k, r.q, r.s));
    }

    public int getNumRealizedArgs() {
      return Long.bitCount(realizedRoles)
          + Long.bitCount(realizedRolesCont)
          + Long.bitCount(realizedRolesRef);
    }
  }

  public static boolean completes(RI completion, RI incomplete) {
    if (incomplete.k >= 0 && incomplete.s == null)
      return completion.k == incomplete.k && completion.s != null;
    if (incomplete.s != null && incomplete.k < 0)
      return completion.s == incomplete.s && completion.k >= 0;
    if (incomplete.k < 0 && incomplete.s == null)
      return completion.k >= 0 && completion.s != null;
    throw new RuntimeException();
  }
  public static boolean incomplete(RI role) {
    return role.k < 0 || role.s == null;
  }

  public static final RI NO_MORE_ARGS = new RI(-1, -1, null);
  public static final RI NO_MORE_ARG_SPANS = new RI(-2, -2, null);
  public static final RI NO_MORE_ARG_ROLES = new RI(-3, -3, null);

  public final class FI {

    public final Frame f; // f==null means only this target has been selected, but it hasn't been labled yet
    public final Span t;  // Currently must be non-null. You could imagine assuming a frame exists THEN finding its extend (...AMR) but thats not what we're currently doing
    public final RILL args;

    // Used to keep track of when (?,s) actions are allowed.
    // When args.realizedSpans.size == possibleArgs.size, you're done
    // (if config.oneArgPerSpan...)
    /**
     * Set of spans which can make an argument allowable a priori.
     * s \not\in possibleArgs  \rightarrow  z_{t,f,k,s}=0 \forall t,f,k
     */
    public final List<Span> possibleArgs;

    public FI(Frame f, Span t, RILL args) {
      this.f = f;
      this.t = t;
      this.args = args;
      this.possibleArgs = null;   // TODO
    }

    public FI prependArg(RI arg) {
      return new FI(this.f, this.t, new RILL(this, arg, this.args));
    }

    // TODO memoize
    public Target target() { return new Target(t); }
  }

  // Note NO_MORE_FRAMES really means "no more FIs".
  // By analogy to RILL and NO_MORE_ROLES, there is no true "no more frames
  // (irrespective of t) in this sentence" action because it doesn't really
  // make sense.
  public static final FI NO_MORE_FRAMES = new State(null, null).new FI(null, null, null);
  public static final FI NO_MORE_TARGETS = new State(null, null).new FI(null, null, null);

  public static final class FILL {

    public final FI item;
    public final FILL next;

    // Caches which should be updateable in O(1) which may inform/prune next() or global features
    public final int numFrameInstances;

    public final fj.data.Set<Span> targetsSelectedSoFar;

    public final boolean noMoreFrames;
    public final boolean noMoreTargets;

    // Should be FILL instead of FI?
    public final FI incomplete;   // can be null

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

      assert highlightedTarget.t != null;
      if (otherFrames.incomplete == null) {
        if (highlightedTarget.f == null)
          incomplete = highlightedTarget;
        else
          incomplete = null;
      } else {
        if (otherFrames.incomplete.t == highlightedTarget.t)
          incomplete = null;
        else
          incomplete = otherFrames.incomplete;
      }
    }

  }

  private Sentence sentence;
  private FNParse label;      // may be null

  // State space pruning
  private FNParseSpanPruning prunedSpans;     // or Map<Span, List<Span>> prunedSpans
  private Map<Span, List<Frame>> prunedFIs;    // TODO fill this in

  // Represents z
  private FILL frames;

//  private IntDoubleUnsortedVector[][][] staticFeatures;   // [t,i,j] == (t,s)
  private Adjoints[][][][] staticRScores;   // (t,s) => [t.start][t.end][s.start][s.end]
  private Adjoints[][][] staticFScores;     // (t,f) => [t.start][t.end][f.id]
  private Config config;

//  private double score;
//  private double loss;
  private Adjoints score;

//  public Model<String> tfksModel;
//  public Model<String> tfsModel;
//  public Model<String> tfkModel;
//  public Model<String> tfModel;
//  public Model<String> tModel;
  public StaticFeatureCache staticFeatureCache; // knows how to compute static features, does so lazily
  // TODO dynamic features?

  public State(FILL frames, Adjoints score) {
    this.frames = frames;
    this.score = score;
  }

  public List<ProductIndex> getStateFeatures() {
    throw new RuntimeException("implement me");
  }

  // Work out scoring after the transition system is working
  public static final Adjoints ZERO = null;
  public static final Random RAND = new Random(9001);
  public static Adjoints randScore() {
    return null;
  }

  /**
   * Replaces the node tail.item with newFrame (in the list this.frames).
   * O(1) if tail == this.frames and O(T) otherwise.
   */
  public State surgery(FILL tail, FI newFrame, Adjoints partialScore) {
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

    Adjoints full = sum(this.score, partialScore);
    return new State(newFILL, full);
  }

  // Sugar
  public static State scons(FI car, FILL cdr, Adjoints score) {
    return new State(cons(car, cdr), score);
  }

  // Sugar
  public static FILL cons(FI car, FILL cdr) {
    return new FILL(car, cdr);
  }

  // Sugar
//  public static RILL cons(RI car, RILL cdr) {
//    return new RILL(car, cdr);
//  }

  // Sugar
  public static void push(edu.jhu.hlt.tutils.Beam<State> beam, State s) {
    double score = s.score.forwards();
    beam.push(s, score);
  }

  public static List<ProductIndex> otimes(ProductIndex newFeat, List<ProductIndex> others) {
    throw new RuntimeException("implement me");
  }


  public static Adjoints f(AT actionType, FI fi, int k, Arg s, List<ProductIndex> stateFeats) {
    throw new RuntimeException("implement me");
  }

  public static Adjoints f(AT actionType, FI fi, Arg s, List<ProductIndex> stateFeats) {
    throw new RuntimeException("implement me");
  }

  public static Adjoints f(AT actionType, FI fi, int k, List<ProductIndex> stateFeats) {
    throw new RuntimeException("implement me");
  }

  public static Adjoints f(AT actionType, Target t, List<ProductIndex> stateFeats) {
    throw new RuntimeException("implement me");
  }

  public static Adjoints f(AT actionType, Target t, Frame f, List<ProductIndex> stateFeats) {
    throw new RuntimeException("implement me");
  }

  public static Adjoints f(AT actionType, Frame f, List<ProductIndex> stateFeats) {
    throw new RuntimeException("implement me");
  }

  // TODO features above should just take FI and RI

  public static Adjoints f(AT actionType, List<ProductIndex> stateFeats) {
    int i = actionType.ordinal();
    int n = AT.values().length;
    ProductIndex y = new ProductIndex(i, n);
    List<ProductIndex> yx = otimes(y, stateFeats);
    throw new RuntimeException("implement me");
  }

  /*
   * objective(s,a) = b0 * modelScore(s,a) + b1 * deltaLoss(s,a) + b2 * rand()
   *   oracle: {b0: 0.1, b1: -10, b2: 0}
   *   mv:     {b0: 1.0, b1: 1.0, b2: 0}
   *   dec:    {b0: 1.0, b1: 0.0, b2: 0}
   */

  enum AT {
    STOP_T, STOP_TF,
    NEW_T, NEW_TF,
    COMPLETE_F,
    STOP_K, STOP_S, STOP_KS,
    NEW_K, NEW_S, NEW_KS,
    COMPLETE_K, COMPLETE_S
  }

  public static class Target {
    public final int start, end;
    public Target(int start, int end) {
      this.start = start;
      this.end = end;
    }
    public Target(Span t) {
      this(t.start, t.end);
    }
    public Span getSpan() { return Span.getSpan(start, end); }
  }

  public static class Arg {
    public final int start, end;
    public Arg(int start, int end) {
      this.start = start;
      this.end = end;
    }
    public Arg(Span t) {
      this(t.start, t.end);
    }
    public Span getSpan() { return Span.getSpan(start, end); }
  }

  /**
   * Ok, screw it, I'm going to do all of the actions out of this one method.
   * The reason that I did this was that:
   * adding an RI -> need to mutate FI -> need to replace node in FILL -> need State
   */
  public void next(edu.jhu.hlt.tutils.Beam<State> beam, boolean oracle) {

    assert config.chooseArgOneStep
        || config.chooseArgRoleFirst
        || config.chooseArgSpanFirst;
    assert !config.roleByRole || config.immediatelyResolveArgs
      : "otherwise you could strange incomplete RIs";

    /*
     * How to do features?
     * dynamic features: compute these on construction
     * static features: how do we keep this O(1)?
     *
     * Store the static scores, and their sums, in the tree.
     * RI: static features for (t,f,k,s) -- need to read this out of State.cachedFeatures
     *
     * Want to cache the dot product AND maintain the backwards structure (for gradients)
     * Adjoints.caching? Sure, need to make your own impl (tutils is messed up, fnparse/Adjoints doesn't cache)
     *
     * Should [Adjoints staticScore] go in?
     * - RI
     * + RILL: roll up Adjoints for all RI
     * - FI: if we didn't have it here, then FILL would have to look through FI to get the RILL Adjoints
     * + FILL: roll up Adjoints for entire subtree
     * + State: they must at least be here because we score States
     *
     * When you do a LL cons, e.g. surgery, then we have to do new Adjoints,
     * as long as the two adjoints in the sum are not re-computing a dot product,
     * then its fine. Since we'll be using State.staticFeatures anyway, then
     * the dot products will be cached anyway.
     *
     * Ok, I have static features figured out, how to handle dynamic features?
     * Could compute one feature vector when next() is called and then product it with:
     *
     * Top level (type-level) features: "What is a good type of action to take here?"
     * - NO_MORE_FRAMES
     * - NO_MORE_TARGETS
     * - Type(t,?)
     * - Type(t,f)
     * - Type(t,f,k,?) 
     * - Type(t,f,?,s)
     * - Type(t,f,k,s)
     *
     * I had said that states corresponding to (t,f,k,s) are unacceptable because
     * they do not commit to any piece of the label. What this paper originally
     * taught me was that you don't have to commit to 1s in the label, but you
     * do have to make Proj(z) smaller. In this sense too, (t,f,k,s) actions are
     * not true actions and must be considered "in the same step" as other actions,
     * which defeats the goal of hiding the loop over (k,s) behind an action.
     * => Poor mans solution: Don't allow (t,f,k,s) actions and figure out where
     *    to put them later!
     *
     * Back to business, how to dynamically featurize things?
     * => Just have one set of State features and then product each of them with
     *    the action type.
     * => Another option is: if the action type knows a bit more than the current
     *    state, e.g. (t,f,k,?) knows it is choosing something to do with k whereas
     *    the state doesn't really featurize k -- it hasn't been chosen yet;
     *    then take products with those features too.
     *    e.g. f(a_{t,f,k,?}) = f(state) \otimes f(k) \otimes I(Type(t,f,k,?))
     */

    if (frames.noMoreFrames)
      return;

    final List<ProductIndex> sf = getStateFeatures();

    push(beam, new State(new FILL(NO_MORE_FRAMES, frames), f(AT.STOP_TF, sf)));

    if (!frames.noMoreTargets) {
      push(beam, new State(new FILL(NO_MORE_TARGETS, frames), f(AT.STOP_T, sf)));
    }

    /* New FI actions *********************************************************/

    // Frames Step 2/2 [immediate]
    if (frames.incomplete != null) {
      Span t = frames.incomplete.t;
      for (Frame f : prunedFIs.get(t)) {
        RILL args2 = null;
        FI fi2 = new FI(f, t, args2);
        push(beam, new State(new FILL(fi2, frames), f(AT.COMPLETE_F, f, sf)));
      }
      if (config.immediatelyResolveFrames)
        return;
    }


    // Frames Step 1/2
    fj.data.Set<Span> tsf = frames.targetsSelectedSoFar;
    int newTF = 0;
    for (Span t : prunedFIs.keySet()) {
      boolean tSeen = tsf.member(t);
      if (!config.oneFramePerSpan || !tSeen) {
        Target tt = new Target(t);

        // (t,)
        if (!frames.noMoreTargets && !tSeen) {
          RILL args = null;
          FI fi = new FI(null, t, args);
          push(beam, new State(new FILL(fi, frames), f(AT.NEW_T, tt, sf)));
          newTF++;
        }

        // (t,f)
        if (config.chooseFramesOnStep) {
          for (Frame f : prunedFIs.get(t)) {
            RILL args2 = null;
            FI fi2 = new FI(f, t, args2);
            push(beam, new State(new FILL(fi2, frames), f(AT.NEW_TF, tt, f, sf)));
            newTF++;
          }
        }
      }
    }
    if (newTF > 0 && config.framesBeforeArgs)
      return;


    // Args Step 2/2 [immediate]
    if (config.immediatelyResolveArgs) {
      for (FILL cur = frames; cur != null; cur = cur.next) {
        RI incomplete = cur.item.args.incomplete;
        if (incomplete != null) {
          FI fi = cur.incomplete;
          if (incomplete.k >= 0) {
            // Loop over s
            int t = -1; // TODO have t:Span need t:int
            for (Span s : prunedSpans.getPossibleArgs(t)) {
              RI newArg = new RI(incomplete.k, incomplete.q, s);
              Adjoints feats = f(AT.COMPLETE_S, fi, new Arg(s), sf);
              push(beam, this.surgery(cur, fi.prependArg(newArg), feats));
            }
          } else if (incomplete.s != null) {
            // Loop over k
            int K = fi.f.numRoles();
            for (int k = 0; k < K; k++) {
              int q = -1; // TODO
              RI newArg = new RI(k, q, incomplete.s);
              Adjoints feats = f(AT.COMPLETE_K, fi, k, sf);
              push(beam, this.surgery(cur, fi.prependArg(newArg), feats));
            }
          } else {
            // Loop over (k,s)
            int K = fi.f.numRoles();
            int t = -1; // TODO have t:Span need t:int
            for (Span s : prunedSpans.getPossibleArgs(t)) {
              Arg ss = new Arg(s);
              for (int k = 0; k < K; k++) {
                int q = -1; // TODO
                RI newArg = new RI(k, q, s);
                Adjoints feats = f(AT.NEW_KS, fi, k, ss, sf);
                push(beam, this.surgery(cur, fi.prependArg(newArg), feats));
              }
            }
          }
          return;
        }
      }
    }


    /* New RI actions *********************************************************/
    for (FILL cur = frames; cur != null; cur = cur.next) {
      FI fi = cur.item;
      assert fi.t != null;

      if (fi.args.noMoreArgs)
        continue;

      // Args Step 1/2
      boolean noMoreNewK = false;
      if (config.chooseArgRoleFirst) {
        // Loop over k
        int KK = fi.args.getNumRealizedArgs();
        int K = fi.f.numRoles();
        if (KK >= K) {
          noMoreNewK = true;
        } else {
          for (int k = 0; k < K; k++) {
            int q = -1;   // TODO
            RI newArg = new RI(k, q, null);
            Adjoints feats = f(AT.NEW_K, fi, k, sf);
            push(beam, this.surgery(cur, fi.prependArg(newArg), feats));
          }
        }
      }
      boolean noMoreNewS = false;
      if (config.chooseArgSpanFirst) {
        // Loop over s
        if (fi.args.realizedSpans.size() >= fi.possibleArgs.size()) {
          noMoreNewS = true;
        } else {
          int t = -1; // TODO have t:Span need t:int
          //        for (Span s : prunedSpans.getPossibleArgs(t)) {
          for (Span s : fi.possibleArgs) {
            RI newArg = new RI(-1, -1, s);
            Adjoints feats = f(AT.NEW_S, fi, new Arg(s), sf);
            push(beam, this.surgery(cur, fi.prependArg(newArg), feats));
          }
        }
      }
      if (config.chooseArgOneStep && !noMoreNewK && !noMoreNewS) {
        // Loop over (k,s)
        int K = fi.f.numRoles();
        int t = -1; // TODO have t:Span need t:int
        for (Span s : prunedSpans.getPossibleArgs(t)) {
          Arg ss = new Arg(s);
          for (int k = 0; k < K; k++) {
            int q = -1;   // TODO
            RI newArg = new RI(k, q, s);
            Adjoints feats = f(AT.NEW_KS, fi, k, ss, sf);
            push(beam, this.surgery(cur, fi.prependArg(newArg), feats));
          }
        }
      }

      // STOP actions
      if (!noMoreNewK && !noMoreNewS)
        push(beam, this.surgery(cur, fi.prependArg(NO_MORE_ARGS), f(AT.STOP_KS, sf)));
      if (!noMoreNewS)
        push(beam, this.surgery(cur, fi.prependArg(NO_MORE_ARG_SPANS), f(AT.STOP_S, sf)));
      if (!noMoreNewK)
        push(beam, this.surgery(cur, fi.prependArg(NO_MORE_ARG_ROLES), f(AT.STOP_K, sf)));


      // Frames Step 2/2 [!immediate]
      if (fi.f == null) {
        // Loop over f
        Target t = new Target(fi.t);
        for (Frame f : prunedFIs.get(fi.t)) {
          Adjoints feats = f(AT.COMPLETE_F, t, f, sf);
          push(beam, new State(new FILL(new FI(f, fi.t, fi.args), cur), feats));
        }

        // We could allow generation of (?,s) actions even if f is not known...
        continue;
      }

      // Args Step 2/2 [!immediate]
      if (!fi.args.noMoreArgs) {
        for (RILL arg = fi.args; arg != null; arg = arg.next) {
          RI ri = arg.item;

          // deltaLoss should have accounted for the cost of missing any
          // possible items stemming from open (?,s) nodes.
          if (ri.k < 0 && ri.s != null && !fi.args.noMoreArgRoles) {
            // loop over roles
            Arg s = new Arg(ri.s);
            int K = fi.f.numRoles();
            for (int k = 0; k < K; k++) {
              int q = -1;   // TODO
              RI newArg = new RI(k, q, ri.s);
              Adjoints feats = f(AT.COMPLETE_K, fi, k, s, sf);
              push(beam, this.surgery(cur, fi.prependArg(newArg), feats));
            }
          } else if (ri.k >= 0 && ri.s == null && !fi.args.noMoreArgSpans) {
            // deltaLoss should have accounted for the cost of missing any
            // possible items stemming from open (k,?) nodes.
            // loop over spans
            int t = -1;   // TODO have t:Span need t:int
            for (Span s : prunedSpans.getPossibleArgs(t)) {
              RI newArg = new RI(ri.k, ri.q, s);
              Adjoints feats = f(AT.COMPLETE_S, fi, ri.k, new Arg(s), sf);
              push(beam, this.surgery(cur, fi.prependArg(newArg), feats));
            }
          } else {
            assert ri.k >= 0 && ri.s != null;
          }
          if (config.roleByRole)
            break;
        } // END ARG LOOP
      }

      if (config.frameByFrame)
        break;
    } // END FRAME LOOP

  }

  public interface Model<X> {
    public Adjoints score(X x);
    public double scoreUpperBound();  // return an upper bound on what score(x).forwards() could be (forall x), or -Infinity if one is not known
  }

  // TODO Try array and hashmap implementations
  public interface StaticFeatureCache {
    public Adjoints scoreT(Span t);
    public Adjoints scoreTF(Span t, Frame f);
    public Adjoints scoreTS(Span t, Span s);
    public Adjoints scoreFK(Frame f, int k, int q);
    public Adjoints scoreFKS(Frame f, int k, int q, Span s);
    public Adjoints scoreTFKS(Span t, Frame f, int k, int q, Span s);
    // etc?
  }
}
