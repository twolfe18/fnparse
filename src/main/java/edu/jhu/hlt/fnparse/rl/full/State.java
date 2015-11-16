package edu.jhu.hlt.fnparse.rl.full;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LabelIndex;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.inference.role.span.FNParseSpanPruning;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.map.IntDoubleEntry;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.prim.vector.IntDoubleDenseVector;
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
   * Does not have any features:Adjoints because this class is static and does
   * not have access to State.staticFeatureCache, see RILL's constructor.
   */
  public static final class RI {
    public final int k;   // k=-1 means only this span has been chosen, but it hasn't been labeled yet
    public final int q;
    public final Span s;

    public RI(int k, int q, Span s) {
      assert s != Span.nullSpan;
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

    /** Returns null if either t or f are null */
    public Pair<Span, Frame> getTF() {
      if (t == null) return null;
      if (f == null) return null;
      return new Pair<>(t, f);
    }
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
//  private FNParse label;      // may be null
  private LabelIndex label;     // may be null

  // State space pruning
  private FNParseSpanPruning prunedSpans;     // or Map<Span, List<Span>> prunedSpans
  private Map<Span, List<Frame>> prunedFIs;    // TODO fill this in

  // Represents z
  private FILL frames;

//  private IntDoubleUnsortedVector[][][] staticFeatures;   // [t,i,j] == (t,s)
  private Adjoints[][][][] staticRScores;   // (t,s) => [t.start][t.end][s.start][s.end]
  private Adjoints[][][] staticFScores;     // (t,f) => [t.start][t.end][f.id]
  private Config config;

  /* Parameters that determine the search objective.
   * objective(s,a) = b0 * modelScore(s,a) + b1 * deltaLoss(s,a) + b2 * rand()
   *   oracle: {b0: 0.1, b1: -10, b2: 0}
   *   mv:     {b0: 1.0, b1: 1.0, b2: 0}
   *   dec:    {b0: 1.0, b1: 0.0, b2: 0}
   */
  private double coefModelScore = 1;
  private double coefLoss = 0;
  private double coefRand = 0;

  // This is the objective being optimzed, which is some combination of model score and loss.
  // Note: This will include the scores of states that lead up to this state (sum over actions).
  private Adjoints score;

  public Weights weights;

  public StaticFeatureCache staticFeatureCache; // knows how to compute static features, does so lazily

  public FrameRolePacking frPacking;

  public Random rand;

  public State(FILL frames, Adjoints score) {
    this.frames = frames;
    this.score = score;
  }

  public List<ProductIndex> getStateFeatures() {
    throw new RuntimeException("implement me");
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
  public static void push(edu.jhu.hlt.tutils.Beam<State> beam, State s) {
    double score = s.score.forwards();
    beam.push(s, score);
  }

  public static List<ProductIndex> otimes(ProductIndex newFeat, List<ProductIndex> others) {
    throw new RuntimeException("implement me");
  }


  public Adjoints f(AT actionType, FI newFI, RI newRI, List<ProductIndex> stateFeats) {
    assert newFI.args.item == newRI;

    /* Get the dynamic features (on FI,RI) ************************************/
    List<ProductIndex> dynFeats = Arrays.asList(ProductIndex.NIL);
    if (newFI.t != null) {
      // Use static features of target span
      List<ProductIndex> at = staticFeatureCache.featT(newFI.t);
      List<ProductIndex> buf = new ArrayList<>(dynFeats.size() * at.size());
      for (ProductIndex yy : dynFeats)
        for (ProductIndex xx : at)
          buf.add(yy.prod(xx.getProdFeatureSafe(), xx.getProdCardinalitySafe()));
      dynFeats = buf;
    }
    if (newFI.f != null) {
      // Use an indicator on frames
      int f = frPacking.index(newFI.f);
      int n = frPacking.getNumFrames();
      for (int i = 0; i < dynFeats.size(); i++)
        dynFeats.set(i, dynFeats.get(i).prod(f, n));
    }
    if (newRI.k >= 0) {
      // Use an indicator on roles
      assert newRI.q < 0 : "not implemented yet";
      assert newFI.f != null : "roles are frame-relative unless you say otherwise";
      int k = frPacking.index(newFI.f, newRI.k);
      int n = frPacking.size();
      for (int i = 0; i < dynFeats.size(); i++)
        dynFeats.set(i, dynFeats.get(i).prod(k, n));
    }
    if (newRI.s != null) {
      assert newFI.t != null : "change me if you really want this";
      List<ProductIndex> at = staticFeatureCache.featTS(newFI.t, newRI.s);
      List<ProductIndex> buf = new ArrayList<>(dynFeats.size() * at.size());
      for (ProductIndex yy : dynFeats)
        for (ProductIndex xx : at)
          buf.add(yy.prod(xx.getProdFeatureSafe(), xx.getProdCardinalitySafe()));
      dynFeats = buf;
    }

    Adjoints score = null;

    if (coefLoss != 0) {

      double fp = 0;
      double fn = 0;
      int possibleFN;

      switch (actionType) {

      /* (t,f) STUFF **********************************************************/
      // NOTE: Fall-through!
      case NEW_TF:
        assert newFI.t != null;
        assert newFI.f != null;
        if (!label.contains(newFI.t, newFI.f));
          fp += 1;
      case NEW_T:
        assert newFI.t != null;
        if (!label.containsTarget(newFI.t))
          fp += 1;
        break;

      // NOTE: Fall-through!
      case STOP_TF:
        // Gold(t,f) - History(t,f)
//        Set<Pair<Span, Frame>> tf = label.buildTargetFrameSet();
        Set<Pair<Span, Frame>> tf = label.borrowTargetFrameSet();
        possibleFN = tf.size();
        for (FILL cur = frames; cur != null; cur = cur.next) {
          Pair<Span, Frame> tfi = cur.item.getTF();
          if (tfi != null) {
//            tf.remove(tfi);
            possibleFN--;
          }
        }
//        fn += 1 * tf.size();
        fn += 1 * possibleFN;
      case STOP_T:
        // Gold(t,f) - History(t,f)
        // Since I only want the size of that set, I don't need to construct it,
        // just iterate through History, decrement a count if !inGold
        Set<Span> t = label.borrowTargetSet();
        Set<Span> check1 = new HashSet<>(), check2 = new HashSet<>();
        possibleFN = t.size();
        for (FILL cur = frames; cur != null; cur = cur.next) {
          Span tt = cur.item.t;
          if (tt != null) {
            /*
             * (t,?) can show up more than once if !config.oneFramePerSpan
             * I believe this means that we must change step 2:
             * Instead of doing an argmax (oneFramePerSpan)
             * We must do a threshold (take all (t,f) s.t. score > 0)
             * Can we have different thresholds for different fertilities?
             *   a) take max(score) if score > t0
             *   b) take secondMax(score) if score > t1 and (a)
             *   etc
             * I think this is a better idea, but for now I will require oneFramePerSpan
             */
            assert config.oneFramePerSpan : "not implemented yet";
            assert check1.add(tt) || check2.add(tt) : "you can have (t,?) then "
                + "(t,f), but there shouldn't be more than two targets activated";
            if (t.contains(cur.item.t))
              possibleFN--;
          }
        }
        fn += 1 * possibleFN;
        break;

      /* (k,s) STUFF **********************************************************/
      case NEW_KS:
        assert newRI.k >= 0;
        assert newRI.q < 0 : "not implemented yet";
        assert newRI.s != null && newRI.s != Span.nullSpan;
        if (!label.contains(newFI.t, newFI.f, newRI.k, newRI.s))
          fp += 1;
        break;
      case NEW_S:
        assert newRI.s != null && newRI.s != Span.nullSpan;
        if (!label.contains(newFI.t, newFI.f, newRI.s))
          fp += 1;
        break;
      case NEW_K:
        assert newRI.q < 0 : "not implemented yet";
        if (!label.contains(newFI.t, newFI.f, newRI.k))
          fp += 1;
        break;

      case STOP_KS:
      case STOP_S:
      case STOP_K:
        // Find all (k,s) present in the label but not yet the history
        assert newFI.t != null && newFI.f != null;
        
        /*
         * Damnit I'm confused!
         * I want to count the Gold (t,f,k,s) which are
         * a) not in history already
         * b) match this STOP action
         * I need to build an index for (b) and then filter (a) by brute force.
         */

//        Set<Pair<Integer, Span>> possibleArgs = label.getArguments(newFI.t, newFI.f);
        Set<int[]> possibleArgs =
          actionType == AT.STOP_KS ? label.get(LabelIndex.encode(newFI.t, newFI.f, newRI.k, newRI.s))
              : actionType == AT.STOP_K ? label.get(LabelIndex.encode(newFI.t, newFI.f, newRI.k))
                  : actionType == AT.STOP_S ? label.get(LabelIndex.encode(newFI.t, newFI.f, newRI.s))
                      : null;
        possibleFN = possibleArgs.size();
        for (RILL cur = newFI.args; cur != null; cur = cur.next) {
          int k = cur.item.k;
          int q = cur.item.q;
          Span s = cur.item.s;
          assert q < 0 : "not implemented yet";
          if (k >= 0 && s != null) {
            if (possibleArgs.contains(LabelIndex.encode(newFI.t, newFI.f, k, s)))
              possibleFN--;
          }
        }
        fn += 1 * possibleFN;
        break;

      default:
        throw new RuntimeException("implement this type: " + actionType);
      }

      assert score == null;
      score = new Adjoints.Constant(coefLoss * (fp + fn));
    }

    if (coefModelScore != 0) {
      Adjoints m = weights.getScore(actionType, dynFeats);
      m = new Adjoints.Scale(coefModelScore, m);
      if (score == null)
        score = m;
      else
        score = new Adjoints.Sum(score, m);
    }

    if (coefRand != 0) {
      double rr = 2 * coefRand * (rand.nextDouble() - 0.5);
      Adjoints r = new Adjoints.Constant(rr);
      if (score != null)
        score = new Adjoints.Sum(score, r);
      else
        score = r;
    }

    throw new RuntimeException("implement me");
  }

  public Adjoints f(AT actionType, FI newFI, List<ProductIndex> stateFeats) {
    return f(actionType, newFI, new RI(-1, -1, null), stateFeats);
  }

  public static Adjoints f(AT actionType, List<ProductIndex> stateFeats) {
    int i = actionType.ordinal();
    int n = AT.values().length;
    ProductIndex y = new ProductIndex(i, n);
    List<ProductIndex> yx = otimes(y, stateFeats);
    throw new RuntimeException("implement me");
  }

  // Action type
  enum AT {
    STOP_T, STOP_TF,
    NEW_T, NEW_TF,
    COMPLETE_F,
    STOP_K, STOP_S, STOP_KS,
    NEW_K, NEW_S, NEW_KS,
    COMPLETE_K, COMPLETE_S
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

    assert !config.frameByFrame || config.immediatelyResolveFrames
      : "otherwise you could strange incomplete FIs";

    assert config.oneFramePerSpan // there is no analog for frames of chooseArg*First
        && (config.oneSpanPerRole || !config.chooseArgRoleFirst)  // chooseArgRoleFirst is the only thing that creates (k,?) states which require the argmax_{spans} step 2
        && (config.oneRolePerSpan || !config.chooseArgSpanFirst)  // similarly for chooseArgSpanFirst
        : "TODO Implement a non-argmax step 2 for these cases"
          + " or use chooseFramesOneSte/chooseArgsOneStep";

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

    /*
     * How to handle q?
     * q really depends on k, in the same way k depends on f.
     * So it should be another dimension in the tensor?
     * Could I just have `class Role` which does k+q?
     * The only thing that we're arguing about is whether the transition system should know about the constraint !k => !q
     * If the transition system doesn't know this, then it has to loop over more k/k+q. Three times more...
     * So it should know. It just makes some things hard, like numRealizedRoles...
     * => Deal with it as you go.
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
        push(beam, new State(new FILL(fi2, frames), f(AT.COMPLETE_F, fi2, sf)));
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
        // (t,)
        if (!frames.noMoreTargets && !tSeen) {
          RILL args = null;
          FI fi = new FI(null, t, args);
          push(beam, new State(new FILL(fi, frames), f(AT.NEW_T, fi, sf)));
          newTF++;
        }

        // (t,f)
        if (config.chooseFramesOnStep) {
          for (Frame f : prunedFIs.get(t)) {
            RILL args2 = null;
            FI fi2 = new FI(f, t, args2);
            push(beam, new State(new FILL(fi2, frames), f(AT.NEW_TF, fi2, sf)));
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
              Adjoints feats = f(AT.COMPLETE_S, fi, newArg, sf);
              push(beam, this.surgery(cur, fi.prependArg(newArg), feats));
            }
          } else if (incomplete.s != null) {
            // Loop over k
            int K = fi.f.numRoles();
            for (int k = 0; k < K; k++) {
              int q = -1; // TODO
              RI newArg = new RI(k, q, incomplete.s);
              Adjoints feats = f(AT.COMPLETE_K, fi, newArg, sf);
              push(beam, this.surgery(cur, fi.prependArg(newArg), feats));
            }
          } else {
            // Loop over (k,s)
            int K = fi.f.numRoles();
            int t = -1; // TODO have t:Span need t:int
            for (Span s : prunedSpans.getPossibleArgs(t)) {
              for (int k = 0; k < K; k++) {
                int q = -1; // TODO
                RI newArg = new RI(k, q, s);
                Adjoints feats = f(AT.NEW_KS, fi, newArg, sf);
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
            RI newRI = new RI(k, q, null);
            FI newFI = fi.prependArg(newRI);
            Adjoints feats = f(AT.NEW_K, newFI, newRI, sf);
            push(beam, this.surgery(cur, newFI, feats));
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
            RI newRI = new RI(-1, -1, s);
            FI newFI = fi.prependArg(newRI);
            Adjoints feats = f(AT.NEW_S, newFI, newRI, sf);
            push(beam, this.surgery(cur, newFI, feats));
          }
        }
      }
      if (config.chooseArgOneStep && !noMoreNewK && !noMoreNewS) {
        // Loop over (k,s)
        int K = fi.f.numRoles();
        int t = -1; // TODO have t:Span need t:int
        for (Span s : prunedSpans.getPossibleArgs(t)) {
          for (int k = 0; k < K; k++) {
            int q = -1;   // TODO
            RI newRI = new RI(k, q, s);
            FI newFI = fi.prependArg(newRI);
            Adjoints feats = f(AT.NEW_KS, newFI, newRI, sf);
            push(beam, this.surgery(cur, newFI, feats));
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
      assert !config.immediatelyResolveFrames;
      if (fi.f == null) {
        // Loop over f
        for (Frame f : prunedFIs.get(fi.t)) {
          FI newFI = new FI(f, fi.t, fi.args);
          Adjoints feats = f(AT.COMPLETE_F, newFI, sf);
          push(beam, this.surgery(cur, newFI, feats));
        }

        // We could allow generation of (?,s) actions even if f is not known...
        continue;
      }

      // Args Step 2/2 [!immediate]
      assert !config.immediatelyResolveArgs;
      if (!fi.args.noMoreArgs) {
        for (RILL arg = fi.args; arg != null; arg = arg.next) {
          RI ri = arg.item;

          // deltaLoss should have accounted for the cost of missing any
          // possible items stemming from open (?,s) nodes.
          if (ri.k < 0 && ri.s != null && !fi.args.noMoreArgRoles) {
            // loop over roles
            int K = fi.f.numRoles();
            for (int k = 0; k < K; k++) {
              int q = -1;   // TODO
              RI newRI = new RI(k, q, ri.s);
              FI newFI = fi.prependArg(newRI);
              Adjoints feats = f(AT.COMPLETE_K, newFI, newRI, sf);
              push(beam, this.surgery(cur, newFI, feats));
            }
          } else if (ri.k >= 0 && ri.s == null && !fi.args.noMoreArgSpans) {
            // deltaLoss should have accounted for the cost of missing any
            // possible items stemming from open (k,?) nodes.
            // loop over spans
            int t = -1;   // TODO have t:Span need t:int
            for (Span s : prunedSpans.getPossibleArgs(t)) {
              RI newRI = new RI(ri.k, ri.q, s);
              FI newFI = fi.prependArg(newRI);
              Adjoints feats = f(AT.COMPLETE_S, newFI, newRI, sf);
              push(beam, this.surgery(cur, newFI, feats));
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

  public static class Weights {
    private LazyL2UpdateVector[] at2w;    // indexed by actionType.ordinal()
    private int dimension;                // length of at2w[i], for feature hahing
    private double l2Lambda;
    private double learningRate;

    public Weights() {
      this(1 * 1024 * 1024, 32, 1e-6, 0.05);
    }

    public Weights(int dimension, int updateInterval, double l2Lambda, double learningRate) {
      this.l2Lambda = l2Lambda;
      this.learningRate = learningRate;
      this.dimension = dimension;
      int N = AT.values().length;
      this.at2w = new LazyL2UpdateVector[N];
      for (int i = 0; i < N; i++)
        this.at2w[i] = new LazyL2UpdateVector(new IntDoubleDenseVector(dimension), updateInterval);
    }

    public Adjoints getScore(final AT actionType, final List<ProductIndex> features) {
      final LazyL2UpdateVector w = at2w[actionType.ordinal()];
      final IntDoubleUnsortedVector fx = new IntDoubleUnsortedVector(features.size());
      for (ProductIndex xi : features)
        fx.add(xi.getProdFeatureModulo(dimension), 1);
      return new Adjoints() {
        public Action getAction() {
          throw new RuntimeException("don't call this");
        }
        public double forwards() {
          return fx.dot(w.weights);
        }
        public void backwards(double dErr_dForwards) {
          double a = learningRate * -dErr_dForwards;
          Iterator<IntDoubleEntry> iter = fx.iterator();
          while (iter.hasNext()) {
            IntDoubleEntry ide = iter.next();
            w.weights.add(ide.index(), a * ide.get());
          }
          w.maybeApplyL2Reg(l2Lambda);
        }
      };
    }
  }

  // TODO Try array and hashmap implementations, compare runtime
  public interface StaticFeatureCache {
    public Adjoints scoreT(Span t);
    public Adjoints scoreTF(Span t, Frame f);
    public Adjoints scoreTS(Span t, Span s);
    public Adjoints scoreFK(Frame f, int k, int q);
    public Adjoints scoreFKS(Frame f, int k, int q, Span s);
    public Adjoints scoreTFKS(Span t, Frame f, int k, int q, Span s);
    // etc?
    public List<ProductIndex> featT(Span t);
    public List<ProductIndex> featTF(Span t, Frame f);
    public List<ProductIndex> featTS(Span t, Span s);
    public List<ProductIndex> featFK(Frame f, int k, int q);
    public List<ProductIndex> featFKS(Frame f, int k, int q, Span s);
    public List<ProductIndex> featTFKS(Span t, Frame f, int k, int q, Span s);
  }
}
