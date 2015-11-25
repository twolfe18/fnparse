package edu.jhu.hlt.fnparse.rl.full;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameArgInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LabelIndex;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.inference.role.span.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.inference.role.span.FNParseSpanPruning;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.rl.full.Config.ArgActionTransitionSystem;
import edu.jhu.hlt.fnparse.rl.full.Config.FrameActionTransitionSystem;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.map.IntIntHashMap;
import edu.jhu.prim.map.LongIntHashMap;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.util.Alphabet;
import fj.Ord;

public class State {

  // For prototyping
  public static final Alphabet<String> ALPH = new Alphabet<>();
  public static int ALPH_DIM = 250_000;
  public static double ALPH_DIM_GROW_RATE = 3;

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
    public final BigInteger sig;

    public RI(int k, int q, Span s, BigInteger sig) {
      this.k = k;
      this.q = q;
      this.s = s;
      this.sig  = sig;
    }

    @Override
    public String toString() {
      String ss = s == null ? "null" : s.shortString();
      return "(RI k=" + k + " s=" + ss + " sig=" + sig + ")";
    }
  }

  public static Adjoints sum(Adjoints partialScore, Adjoints prefixScore) {
    return new Adjoints.Sum(partialScore, new Adjoints.OnlyShowScore("Prefix", prefixScore));
  }

  public final class RILL {

    public final RI item;
    public final RILL next;

    // Caches which should be updateable in O(1) which may inform/prune next() or global features
    public final long realizedRoles;  // does 64 roles play when you have to deal with C/R roles?
    public final long realizedRolesCont;
    public final long realizedRolesRef;

    // TODO See LazySet in Node code?
    // Does not count nullSpan
    public final fj.data.Set<Span> realizedSpans;

    // Sum of static features for all RI in this list
//    public final Adjoints staticFeatures;

    // Product of a unique prime at every (t,f,k,s)
    public final BigInteger sig;

    @Override
    public String toString() {
      String ss = item.s == null ? "?" : item.s.shortString();
      String ks = item.k < 0 ? "?" : String.valueOf(item.k);
      return ks + "@" + ss + " -> " + next;
    }

    /**
     * @param f is needed in order to lookup features.
     * @param r is the new RI being prepended.
     * @param l is the list being prepeded to, may be null when creating a list with one element
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
      if (l == null) {
        realizedRoles = rm;
        realizedRolesCont = (r.q == CONT ? rm : 0);
        realizedRolesRef = (r.q == REF ? rm : 0);
        if (r.s == null)
          realizedSpans = fj.data.Set.<Span>empty(Ord.comparableOrd());
        else
          realizedSpans = fj.data.Set.single(Ord.comparableOrd(), r.s);
//        staticFeatures = info.staticFeatureCache.scoreTFKS(f.t, f.f, r.k, r.q, r.s);
        sig = r.sig;
      } else {
        realizedRoles = l.realizedRoles | rm;
        realizedRolesCont = l.realizedRolesCont | (r.q == CONT ? rm : 0);
        realizedRolesRef = l.realizedRolesRef | (r.q == REF ? rm : 0);
        realizedSpans = r.s == null || r.s == Span.nullSpan
            ? l.realizedSpans : l.realizedSpans.insert(r.s);
//        staticFeatures = sum(
//            l.staticFeatures,
//            info.staticFeatureCache.scoreTFKS(f.t, f.f, r.k, r.q, r.s));
        sig = l.sig.multiply(r.sig);
      }
    }

    public int getNumRealizedArgs() {
      return Long.bitCount(realizedRoles)
          + Long.bitCount(realizedRolesCont)
          + Long.bitCount(realizedRolesRef);
    }
  }

  public final class FI {

    public final Frame f; // f==null means only this target has been selected, but it hasn't been labled yet
    public final Span t;  // Currently must be non-null. You could imagine assuming a frame exists THEN finding its extend (...AMR) but thats not what we're currently doing
    public final RILL args;

    // noMoreArgs is the conjunction of both of these
    public final boolean noMoreArgSpans;
    public final boolean noMoreArgRoles;

    // You can set this for debugging, not used for anything important.
    public FrameInstance goldFI;

    @Override
    public String toString() {
      String ts = t == null ? "null" : t.shortString();
      String fs = f == null ? "null" : f.getName();
      return "(FI " + ts + " " + fs
          + " nArgs=" + (args == null ? 0 : args.getNumRealizedArgs())
          + " nmaSpans=" + noMoreArgSpans
          + " nmaRoles=" + noMoreArgRoles
          + (goldFI == null ? "" : " " + Describe.frameInstaceJustArgsTerse(goldFI))
          + ")";
    }

    /** Mutates, returns this */
    public FI withGold(FrameInstance fi) {
      this.goldFI = fi;
      return this;
    }

    /*
     * sig is a product of primes.
     * we use integer multiplication because it is commutative, like set add.
     * it turns out that the elements in our set have some structure,
     *   specifically they are products of indices like t*f*k*s
     * but if our elements are represented as primes, we can't factor them
     * we could have chosen addition in the outer monoid:
     *   the only trouble is with addition:
     *   if we want to have the property that sig = list of monoid operations
     *   doesn't collide, then we need to ensure something like
     *   x + y == x + z => y == z
     *   with mult/primes this is easy: we can talk about prime factorizations of x+y or x+z, which are unique
     *   with addition, the naive thing to do is use powers of two... need T*K*F*S bits... too big
     *   with mult/primes (optimally), you need (less than) D*log(P) where D is the number of realized (t,f,k,s) and P is the Dth prime.
     *   e.g. D=15 => P=47 => D*log(P) = 84 bits...
     *   this bound is based on the trick that you can sort the (t,f,k,s) by some a priori score and assign small primes to things likely to be in the set (product)
     *   this does grow faster than I thought:
     *   awk 'BEGIN{p=1} {p *= $1; print NR, $1, log(p)/log(2)}' <(zcat primes1.byLine.txt.gz) | head -n 20
1 2 1
2 3 2.58496
3 5 4.90689
4 7 7.71425
5 11 11.1737
6 13 14.8741
7 17 18.9616
8 19 23.2095
9 23 27.7331
10 29 32.5911
11 31 37.5452
12 37 42.7547
13 41 48.1123
14 43 53.5385
15 47 59.0931
16 53 64.821
17 59 70.7037
18 61 76.6344
19 67 82.7005
20 71 88.8502
     * This means that even if you guess perfectly on your sort order,
     * you still can only fit 15 items in a uint64_t.
     *
     * The way I have it implemented (no sort over primes), the number of bits
     * needed is going to be something like 580 bits for 25 (t,f,k,s) and
     * 900 for 40. It should grow slightly faster than linear in nnz.
     *
     * zcat data/primes/primes1.byLine.txt.gz | shuf | awk 'BEGIN{p=1} {p *= $1; if (NR % 25 == 0) { print log(p)/log(2); p=1; }}' | head -n 1000 | plot
     */

    public FI(Frame f, Span t, RILL args) {
      this(f, t, args, false, false);
    }

    public FI(Frame f, Span t, RILL args,
        boolean noMoreArgSpans, boolean noMoreArgRoles) {
      this.f = f;
      this.t = t;
      this.args = args;
      this.noMoreArgSpans = noMoreArgSpans;
      this.noMoreArgRoles = noMoreArgRoles;
    }

    public boolean argHasAppeared(int k) {
      if (args == null)
        return false;
      return (args.realizedRoles & (1 << k)) != 0;
    }

    public boolean spanHasAppeared(Span s) {
      if (args == null)
        return false;
      return args.realizedSpans.member(s);
    }

    public FI prependArg(RI arg) {
      // TODO check noMore* flags?
      RILL rill = new RILL(this, arg, this.args);
      return new FI(this.f, this.t, rill).withGold(goldFI);
    }

    /*
     * We need to include a prime for noMore* because they are legitimately
     * different states (z will include more 0s than if we didn't add the constraint).
     * This does not work if you have other prune operations, you would need a
     * prime for every index in z which was set to 0. This is a trick which works
     * because there is exactly one way (up to re-ordering) to get a subset of 2^N:
     * have commit actions for the 1 bits and have one extra item for "no more".
     */
    /**
     * TODO: Optimization to make sigs smaller/faster:
     * When we're not doing frameId (frameMode == FrameActionTransitionSystem.ASSUME_FRAMES_ARE_GIVEN),
     * we don't need to multiply in anything to do with (t:Span, f:Frame).
     * Perhaps we might need to index t with 1..T, but this should lead to much
     * smaller primes. Figure out how to do this when speed becomes a problem.
     *
     * TODO: Optimization to make sigs smaller/faster:
     * Instead of finding a prime by indices like Span.index(t) and f.getId(),
     * put them into a inference-specific Alphabet so that they are densely
     * packed into [0,N). The trouble with this is that the Alphabet either needs
     * to be over big things like (t,f,k,s), or assumptions need to be made about
     * how big the t/f/k/s alphabet can get...
     */
    public BigInteger getSig() {
      // NOTE: Since noMoreArgs = noMoreSpans & noMoreRoles, don't use a separate
      // prime for noMoreArgs.
      BigInteger b = args == null ? BigInteger.ONE : args.sig;

      // Need primes for (t,f)...
      // Want to be able to tell apart the states...
      // What happens when a (t,?) is completed?
      assert t != null;
      if (f == null)
        b = b.multiply(BigInteger.valueOf(info.primes.get(t)));
      else
        b = b.multiply(BigInteger.valueOf(info.primes.get(t, f)));

      long p = 1;
      if (noMoreArgSpans)
        p *= info.primes.getSpecial(t, f, SpecialRole.NO_MORE_ARG_SPANS);
      if (noMoreArgRoles)
        p *= info.primes.getSpecial(t, f, SpecialRole.NO_MORE_ARG_ROLES);
      if (p == 1)
        return b;
      else
        return b.multiply(BigInteger.valueOf(p));
    }

    /** Returns null if either t or f are null */
    public Pair<Span, Frame> getTF() {
      if (t == null) return null;
      if (f == null) return null;
      return new Pair<>(t, f);
    }

    /*
     * WARNING: Code below is not bad (like it may seem)
     *
     * Incomplete FI/RI are not the same as CLOSE/NO_MORE* FI/RI
     * They both need to have a prime multiplied into the signature to distinguish states
     * BUT we don't want to put incomplete FI/RI into their LL because this means there may be more than one sig that leads to the completed form
     */

    /*
     * Clarity:
     * If you put FI/RI representing NO_MORE* into FILL/RILLs, then you have to explicitly skip over them in for loops.
     * That sucks, so just put NO_MORE* in State/FI.
     * You given State/FI methods which return their respective type, but with NO_MORE* enforced.
     * The Signatures for these types must check special flags which say if NO_MORE* have been added to the State/FI.
     * Flags in FI/RI are NOT NEEDED because NO_MORE* is not accumulated by cons (LL constructor).
     */

    /* noMore(Targets|Frames) are not incomplete nodes, thus should appear as FI in a FILL
     * The alternative is to put them in State.
     *
     * They should at least match now NO_MORE_ARG* work.
     * Those are:
     * 1) real RI nodes
     * 2) flags in RILL
     *
     * Can I think of a reason to not do the following:
     * 1) never have FI/RI nodes for these NO_MORE* nodes
     * 2) have both a flag and sig in the parent of the LL which holds notions of NO_MORE
     *
     * In a way FI is (a special) first node of RILL
     * Same for State and FILL
     * The only thing I could see wanting by not putting these special aggregates in the first node is the ability to "replay" time.
     * Also its a bit more conceptually clean if you don't have any speical first nodes
     * But do we absolutely need special first nodes for anything in particular?
     * Every LL node could store all the aggregates.
     * Having a special first node lets each LL node not have extra fields and join operations
     *
     * Oooh, lets say that you wanted to do NO_MORE_ARGS on a FI/RILL which is not at the head of State/FILL
     * We could do this with a special node (FI) by keeping all of the an entire RILL and only making a new FI node.
     * one new FI node is about the same as prepending a RI to a RILL, so no real win there.
     *
     * Bottom up?
     * If you started thinking about this tree in bottom-up fashion, you would have
     * RI = {t,f,k,s}
     * If you wanted to group these things with special FI/RILL nodes, you could then drop the (t,f), but only as an optimization.
     * This is a nice way to think about this: we are always conceptually equal in some way to a LL.
     * This is how we get O(1) updates.
     * The reason to have State/FI/RILL nodes are that they are indices on accumulators that we wish to keep for global features.
     * Since the State/FI/RILL hierachy is fixed depth, we can say that mutating it is O(1), which lets us do cool things like add an argument to the second (t,f) while the first is still open.
     *
     * Thats an interesting way to think about "frozen":
     * You are always adding nodes to a LL, but you have a dummy node called frozen (which may point to more items in the LL)
     * When you want to add children to an item in this LL, you may only do so if it appears before frozen in the LL
     * Freezing a node may only be done by moving it behind the frozen node.
     * Since we are O(n) where n is the number of nodes before frozen, we can allow any of the 1..n nodes in front to be pushed back behind frozen, triggering an O(n) operation (mutating a list item at depth n is O(n))
     *
     * It would be nice to connect this to what I was thinking about coref,
     * and come up with a universal description of how you do these append-only list/tree-like persistent state representations with accumulators for global features...
     * But I want to get this working soon.
     */

    public FI noMoreArgs() {
      assert !noMoreArgRoles || !noMoreArgSpans;
      return new FI(f, t, args, true, true).withGold(goldFI);
    }

    public FI noMoreArgSpans() {
      assert !noMoreArgSpans;
      return new FI(f, t, args, true, noMoreArgRoles).withGold(goldFI);
    }

    public FI noMoreArgRoles() {
      assert !noMoreArgRoles;
      return new FI(f, t, args, noMoreArgSpans, true).withGold(goldFI);
    }
  }

  public final class FILL {

    public final FI item;
    public final FILL next;

    // Caches which should be updateable in O(1) which may inform/prune next() or global features
    public final int numFrameInstances;

    public final fj.data.Set<Span> targetsSelectedSoFar;

    // Includes primes for noMore*
    public final BigInteger sig;

    /**
     * New target chosen (next actions will range over features)
     */
    public FILL(FI highlightedTarget, FILL otherFrames) {
      item = highlightedTarget;
      next = otherFrames;
      if (otherFrames == null) {
        numFrameInstances = 1;
        assert highlightedTarget.t != null;
        targetsSelectedSoFar = fj.data.Set.single(Ord.comparableOrd(), highlightedTarget.t);
        sig = highlightedTarget.getSig();
      } else {
        numFrameInstances = otherFrames.numFrameInstances + 1;
        targetsSelectedSoFar = highlightedTarget.t != null
            ? otherFrames.targetsSelectedSoFar.insert(highlightedTarget.t)
                : otherFrames.targetsSelectedSoFar;
        sig = highlightedTarget.getSig().multiply(otherFrames.sig);
      }
    }

    public int getNumRealizedArgs() {
      if (item.args == null)
        return 0;
      return item.args.getNumRealizedArgs();
    }
  }


  // Represents z
  public final FILL frames;

  // A pointer into frames, which by default points to the head, of the first
  // FI which is capable of generating some actions. Some FI will be complete,
  // as in some span has been chosen for every role (if ROLE_BY_ROL or ROLE_FIRST),
  // etc, and can generate no more actions, at the cost of iterating over all
  // possible roles (e.g.).
  // TODO Replace this with a "frozen" list which contains only FI wihch are done
  // (yield no actions). This is desirable over the current solution (firstNotDone)
  // because it means that surgery operations (copy a prefix of a LL) is cheaper.
  // The current solution was chosen because it is easier to implement.
  /*
   * How to implement frozen/done list:
   * Option 1: The action that leads to the done FI needs to move that FI to frozen.
   *           Requires "one step of look ahead"
   *           Normally you know a FI is done when no actions are generated from it.
   * Option 2: Clean up after an done FI.
   *           Requires a mutation to frames:FILL and frozen:FILL when you realize a FI is done.
   * no clear winner, do later.
   */
  private FILL firstNotDone;

  // Switch back to the the traditional meaning: noMoreFrames refers no (?,f) not (t,f)
  // We just won't allow any operations that set noMoreFrames=true && noMoreTargets=false;
  public final boolean noMoreFrames;
  public final boolean noMoreTargets;

  // When you add a (k,?) or (?,s) RI, you must immediately resolve it.
  // This pointer should be set to the RILL which has that RI at its head.
  // If this is non-null, only actions that complete that RI will be generated,
  // and the next State will have incomplete=null.
  // NOTE: If you have an incomplete FI, there is only one FILL in state, so you
  // can just check that, you don't need to put it here.
  public final Incomplete incomplete;

  // This is the objective being optimized, which is some combination of model score and loss.
  // Note: This will include the scores of states that lead up to this state (sum over actions).
  public Adjoints score;

  // Everything that is annoying to copy in State
  public final Info info;

  public State(FILL frames, boolean noMoreFrames, boolean noMoreTargets, Incomplete incomplete, Adjoints score, Info everythingElse) {
    this.frames = frames;
    this.noMoreFrames = noMoreFrames;
    this.noMoreTargets = noMoreTargets;
    this.incomplete = incomplete;
    this.score = score;
    this.info = everythingElse;
  }

  /** Everything that is annoying to copy in State */
  public static class Info {
    public Sentence sentence;
    public LabelIndex label;     // may be null

    // State space pruning
    private FNParseSpanPruning prunedSpans;     // or Map<Span, List<Span>> prunedSpans
    private Map<Span, List<Frame>> prunedFIs;    // TODO fill this in

    // Parameters of transition system
    private Config config;

    /* Parameters of search.
     * objective(s,a) = b0 * modelScore(s,a) + b1 * deltaLoss(s,a) + b2 * rand()
     *   oracle: {b0: 0.1, b1: -10, b2: 0}
     *   mv:     {b0: 1.0, b1: 1.0, b2: 0}
     *   dec:    {b0: 1.0, b1: 0.0, b2: 0}
     */
    public double coefModelScore;
    public double coefLoss;
    public double coefRand;

    public Weights weights;

    public StaticFeatureCache staticFeatureCache; // knows how to compute static features, does so lazily

    public FrameRolePacking frPacking;

    // Map (t,f,k,s) -> P, where P is the set primes (though any given
    // implementation may use P_n, the first n primes, and map multiple (t,f,k,s)
    // to the same prime via hashing).
    public PrimesAdapter primes;

    public Random rand;

    /** Does not include nullSpan */
    public List<Span> getPossibleArgs(FI fi) {
      assert fi.t != null;
      assert fi.f != null;
      FrameInstance key = FrameInstance.frameMention(fi.f, fi.t, sentence);
      List<Span> all = prunedSpans.getPossibleArgs(key);
      List<Span> nn = new ArrayList<>(all.size() - 1);
      for (Span s : all)
        if (s != Span.nullSpan)
          nn.add(s);
      return nn;
    }

    public void setTargetPruningToGoldLabels() {
      if (label == null)
        throw new IllegalStateException("need a label for this operation");
      prunedFIs = new HashMap<>();
      for (FrameInstance fi : label.getParse().getFrameInstances()) {
        Span t = fi.getTarget();
        Frame f = fi.getFrame();
        List<Frame> other = prunedFIs.put(t, Arrays.asList(f));
        assert other == null;
      }
    }

    /** Assumes that the given sentence has parses already */
    public void setArgPruningUsingSyntax(DeterministicRolePruning.Mode mode, boolean includeGoldSpansIfMissing) {
      DeterministicRolePruning drp = new DeterministicRolePruning(mode, null, null);
      StageDatumExampleList<FNTagging, FNParseSpanPruning> inf = drp.setupInference(Arrays.asList(label.getParse()), null);
      prunedSpans = inf.decodeAll().get(0);
      if (includeGoldSpansIfMissing) {
        if (label == null)
          throw new RuntimeException("you need a label to perform this operation");
        int adds = 0;
        int realized = 0;
        int present = 0;
        List<FrameInstance> fis = label.getParse().getFrameInstances();
        for (FrameInstance fi : fis) {
          FrameInstance key = FrameInstance.frameMention(fi.getFrame(), fi.getTarget(), fi.getSentence());
          present += prunedSpans.getPossibleArgs(key).size();
          int K = fi.getFrame().numRoles();
          for (int k = 0; k < K; k++) {
            Span s = fi.getArgument(k);
            if (s != Span.nullSpan) {
              realized++;
              if (!prunedSpans.getPossibleArgs(key).contains(s)) {
                adds++;
                prunedSpans.addSpan(key, s);
              }
            }
          }
        }
        Log.debug("includeGoldSpansIfMissing: adds=" + adds
            + " realized=" + realized + " presentBefore=" + present
            + " nFI=" + label.getParse().numFrameInstances()
            + " nTokens=" + sentence.size());
      }
    }
  }

  public State noMoreFrames(Adjoints partialScore) {
    assert !noMoreFrames;
    return new State(frames, true, true, incomplete, sum(partialScore, score), info);
  }

  public State noMoreTargets(Adjoints partialScore) {
    assert !noMoreTargets;
    return new State(frames, noMoreFrames, true, incomplete, sum(partialScore, score), info);
  }

  public String show() {
    StringBuilder sb = new StringBuilder();
    sb.append("(State\n");
    sb.append("  nmT=" + noMoreTargets + "\n");
    sb.append("  nmF=" + noMoreFrames + "\n");
    sb.append("  sig=" + getSig() + "\n");
    sb.append("  inc=" + incomplete + "\n");
    for (FILL cur = frames; cur != null; cur = cur.next) {
      FI fi = cur.item;
      sb.append("    " + fi + "\n");
      for (RILL curr = fi.args; curr != null; curr = curr.next) {
        RI ri = curr.item;
        sb.append("      " + ri + "\n");
      }
    }
    sb.append(")");
    return sb.toString();
  }

  public State setFramesToGoldLabels() {
    if (info.label == null)
      throw new IllegalStateException("need a label for this operation");
    FILL fis = null;
    FNParse y = info.label.getParse();

    // Sort the FIs by order of appearance (rather than the default, which I think is by frame)
    List<FrameInstance> fisl = new ArrayList<>();
    fisl.addAll(y.getFrameInstances());
    Collections.sort(fisl, FrameInstance.BY_SENTENCE_POSITION_ASC);

    int n = fisl.size();
    for (int i = n - 1; i >= 0; i--) {
      FrameInstance fi = fisl.get(i);
      FI fic = new FI(fi.getFrame(), fi.getTarget(), null).withGold(fi);
      fis = new FILL(fic, fis);
    }
    return new State(fis, noMoreFrames, noMoreTargets, incomplete, score, info);
  }

  public static void ff(List<ProductIndex> addTo, String featName) {
    int i = ALPH.lookupIndex(featName, true);
    if (ALPH.size() >= ALPH_DIM)
      ALPH_DIM = (int) (ALPH_DIM * ALPH_DIM_GROW_RATE + 1);
    addTo.add(new ProductIndex(i, ALPH_DIM));
  }

  private List<ProductIndex> _stateFeaturesMemo = null;
  public List<ProductIndex> getStateFeatures() {
    if (_stateFeaturesMemo != null) {
      Log.debug("returning state features memo");
      return _stateFeaturesMemo;
    }

    Log.debug("computing state features");

    List<ProductIndex> f = new ArrayList<>();
    // TODO more features
    if (frames == null) {
      ff(f, "noFramesYet");
    } else {
      ff(f, "nFI=" + frames.numFrameInstances);
      ff(f, "nArgs[0]=" + frames.getNumRealizedArgs());
//      if (frames.item.args.next != null)
//        ff(f, "nArgs[1]=" + frames.item.args.next.getNumRealizedArgs());
//      else
//        ff(f, "nArgs[1]=NA");
      ff(f, "nFI.t=" + frames.targetsSelectedSoFar.size());
    }

    _stateFeaturesMemo = f;
    return f;
  }

  public BigInteger getSig() {
    BigInteger p = BigInteger.ONE;
    if (frames != null) {
      p = frames.sig;
    }
    if (noMoreTargets) {
      int x = info.primes.getSpecial(SpecialFrame.NO_MORE_TARGETS);
      p = p.multiply(BigInteger.valueOf(x));
    }
    if (noMoreFrames) {
      int x = info.primes.getSpecial(SpecialFrame.NO_MORE_FRAMES);
      p = p.multiply(BigInteger.valueOf(x));
    }
    if (incomplete != null) {
      p = p.multiply(incomplete.getSig());
    }
    return p;
  }

  @Override
  public int hashCode() {
    return getSig().hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof State) {
      BigInteger os = ((State) other).getSig();
      return getSig().equals(os);
    }
    return false;
  }

  /**
   * Search (for tail) and replace (with newFrame) in this.frames.
   * O(1) if always tail == this.frames and O(T) otherwise.
   */
  public State surgery(FILL tail, FI newFrame, Incomplete newIncomplete, Adjoints newStateScore) {
    assert !noMoreFrames;

    // Pick off the states between this.frames and tail
    List<FI> copy = new ArrayList<>();
    for (FILL c = this.frames; c != tail; c = c.next) {
      assert c != null : "didn't find tail in this.frames?";
      copy.add(c.item);
    }

    // Construct the new FILL
    FILL newFILL = new FILL(newFrame, tail.next);

    // Prepend all the copied nodes between this.frame and tail
    for (int i = copy.size() - 1; i >= 0; i--)
      newFILL = new FILL(copy.get(i), newFILL);

    return new State(newFILL, noMoreFrames, noMoreTargets, newIncomplete, newStateScore, info);
  }

  // Sugar
  public static void push(Beam next, Beam overallBestStates, State s) {
    assert !(s.score instanceof Adjoints.Caching);
    s.score = new Adjoints.Caching(s.score);
    Log.debug("score: " + s.score.forwards());
    Log.debug("because: " + s.score);
    System.out.println();
    next.offer(s);
    overallBestStates.offer(s);
  }

  public static List<ProductIndex> otimes(ProductIndex newFeat, List<ProductIndex> others) {
    throw new RuntimeException("implement me");
  }

  public Adjoints f(AT actionType, FI newFI, RI newRI, List<ProductIndex> stateFeats) {
    assert (info.coefLoss != 0) || (info.coefModelScore != 0) || (info.coefRand != 0);

    // TODO I do not know why I added this...
//    /* Get the dynamic features (on FI,RI) ************************************/
//    List<ProductIndex> dynFeats = Arrays.asList(ProductIndex.NIL);
//    if (newFI.t != null) {
//      // Use static features of target span
//      List<ProductIndex> at = info.staticFeatureCache.featT(newFI.t);
//      List<ProductIndex> buf = new ArrayList<>(dynFeats.size() * at.size());
//      for (ProductIndex yy : dynFeats)
//        for (ProductIndex xx : at)
//          buf.add(yy.prod(xx.getProdFeatureSafe(), xx.getProdCardinalitySafe()));
//      dynFeats = buf;
//    }
//    if (newRI != null && newRI.k >= 0) {
//      // Use an indicator on roles
//      assert newRI.q < 0 : "not implemented yet";
//      assert newFI.f != null : "roles are frame-relative unless you say otherwise";
//      int k = info.frPacking.index(newFI.f, newRI.k);
//      int n = info.frPacking.size();
//      assert k < n;
//      for (int i = 0; i < dynFeats.size(); i++)
//        dynFeats.set(i, dynFeats.get(i).prod(k, n));
//    } else if (newFI.f != null) {
//      // Since role indicator feature includes frame, only need this if not using roles.
//      // Use an indicator on frames
//      int f = newFI.f.getId();
//      int n = info.frPacking.getNumFrames();
//      assert f < n;
//      for (int i = 0; i < dynFeats.size(); i++)
//        dynFeats.set(i, dynFeats.get(i).prod(f, n));
//    }
//    if (newRI != null && newRI.s != null) {
//      assert newFI.t != null : "change me if you really want this";
//      List<ProductIndex> at = info.staticFeatureCache.featTS(newFI.t, newRI.s);
//      List<ProductIndex> buf = new ArrayList<>(dynFeats.size() * at.size());
//      for (ProductIndex yy : dynFeats)
//        for (ProductIndex xx : at)
//          buf.add(yy.prod(xx.getProdFeatureSafe(), xx.getProdCardinalitySafe()));
//      dynFeats = buf;
//    }


    Adjoints score = null;

    if (info.coefLoss != 0) {

      double fp = 0;
      double fn = 0;
      int possibleFN;

      switch (actionType) {

      /* (t,f) STUFF **********************************************************/
      // NOTE: Fall-through!
      case COMPLETE_F:
      case NEW_TF:
        assert newFI.t != null;
        assert newFI.f != null;
        if (!info.label.contains(newFI.t, newFI.f));
          fp += 1;
      case NEW_T:
        assert newFI.t != null;
        if (!info.label.containsTarget(newFI.t))
          fp += 1;
        break;

      // NOTE: Fall-through!
      case STOP_TF:

        assert false: "properly implement FN counting for args";

        // TODO STOP_T and STOP_TF stop new FI from being created, thus any roles
        // for FI not yet realized should be counted.

        // Map<TF, List<FrameArgInstance>> gold: lets you find all the args which
        // correspond to a missing TF.

        // Cheap hack instead: 
        // Don't allow STOP_TF and STOP_T actions if we're not doing frame prediction.

        // Gold(t,f) - History(t,f)
        Set<Pair<Span, Frame>> tf = info.label.borrowTargetFrameSet();
        possibleFN = tf.size();
        for (FILL cur = frames; cur != null; cur = cur.next) {
          Pair<Span, Frame> tfi = cur.item.getTF();
          if (tfi != null)
            possibleFN--;
        }
        fn += 1 * possibleFN;
      case STOP_T:

        assert false: "properly implement FN counting for args";

        // Gold(t,f) - History(t,f)
        // Since I only want the size of that set, I don't need to construct it,
        // just iterate through History, decrement a count if !inGold
        Set<Span> t = info.label.borrowTargetSet();
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
      case COMPLETE_K:
      case COMPLETE_S:
        assert newRI.k >= 0;
        assert newRI.q < 0 : "not implemented yet";
        assert newRI.s != null && newRI.s != Span.nullSpan;
        if (!info.label.contains(newFI.t, newFI.f, newRI.k, newRI.s)) {
          
          if (newRI.k == 1 && newRI.s == Span.getSpan(4, 6) && newFI.t == Span.getSpan(3, 4)) {
//          if (newRI.k == 1 && newRI.s == Span.getSpan(4, 6)) {
            Log.warn("wat");
          }
          
          fp += 1;
        }
        break;
      case NEW_S:
        assert newRI.s != null && newRI.s != Span.nullSpan;
        if (!info.label.contains(newFI.t, newFI.f, newRI.s))
          fp += 1;
        break;
      case NEW_K:
        assert newRI.q < 0 : "not implemented yet";
        if (!info.label.contains(newFI.t, newFI.f, newRI.k))
          fp += 1;
        break;

      case STOP_KS:     // given (t,f): z_{t,f,k,s}=0
        assert info.config.argMode == ArgActionTransitionSystem.ONE_STEP;
      case STOP_S:      // forall k, given (t,f): z_{t,f,k,s}=0
      case STOP_K:      // forall s, given (t,f): z_{t,f,k,s}=0

        // Find all (k,s) present in the label but not yet the history
        assert newFI.t != null && newFI.f != null;
        /*
         * I want to count the Gold (t,f,k,s) which are
         * a) not in history already
         * b) match this STOP action
         * I need to build an index for (b) and then filter (a) by brute force.
         */
        Set<FrameArgInstance> goldItems;
        if (actionType == AT.STOP_KS)
          goldItems = info.label.get(newFI.t, newFI.f, newRI.k, newRI.s);
        else if (actionType == AT.STOP_K)
          goldItems = info.label.get(newFI.t, newFI.f, newRI.k);
        else if (actionType == AT.STOP_S)
          goldItems = info.label.get(newFI.t, newFI.f, newRI.s);
        else
          throw new RuntimeException();
        possibleFN = goldItems.size();
        for (RILL cur = newFI.args; cur != null; cur = cur.next) {
          int k = cur.item.k;
          int q = cur.item.q;
          Span s = cur.item.s;
          assert q < 0 : "not implemented yet";
          if (k >= 0 && s != null) {
            if (goldItems.contains(new FrameArgInstance(newFI.f, newFI.t, k, s)))
              possibleFN--;
          }
        }
        fn += 1 * possibleFN;
        break;

      default:
        throw new RuntimeException("implement this type: " + actionType);
      }

      assert score == null;
      score = new Adjoints.Scale(info.coefLoss, new LossA(fp, fn));
    }

    if (info.coefModelScore != 0) {
      Adjoints m = info.weights.getScore(actionType, stateFeats);
      m = new Adjoints.Scale(info.coefModelScore, m);
      if (score == null)
        score = m;
      else
        score = new Adjoints.Sum(score, m);
    }

    if (info.coefRand != 0) {
      double rr = 2 * (info.rand.nextDouble() - 0.5);
      Adjoints r = new Adjoints.Constant(rr);
      r = new Adjoints.Scale(info.coefRand, r);
      if (score != null)
        score = new Adjoints.Sum(score, r);
      else
        score = r;
    }

    assert score != null;
    return score;
  }

  // For prettier logs :)
  public static class LossA extends Adjoints.Constant {
    private static final long serialVersionUID = 6821523594344630559L;
    private double fp, fn;
    public LossA(double fp, double fn) {
      super(fp + fn);
      this.fp = fp;
      this.fn = fn;
    }
    @Override
    public String toString() {
      return String.format("(Loss fp=%.1f fn=%.1f)", fp,fn);
    }
  }

  public Adjoints f(AT actionType, FI newFI, List<ProductIndex> stateFeats) {
    return f(actionType, newFI, null, stateFeats);
  }

  // Only used for noMoreTargets and noMoreFrames
  public Adjoints f(AT actionType, List<ProductIndex> stateFeats) {
//    return info.weights.getScore(actionType, stateFeats);
    return f(actionType, null, null, stateFeats);
  }

  /**
   * AT == Action type
   * This is how actions are scored: we have State (dynamic) features
   * which are producted with the action type to get a featurized score.
   */
  enum AT {
    STOP_T, STOP_TF,
    NEW_T, NEW_TF,
    COMPLETE_F,
    STOP_K, STOP_S, STOP_KS,
    NEW_K, NEW_S, NEW_KS,
    COMPLETE_K, COMPLETE_S
  }

  private void nextComplete(Beam beam, Beam overall, List<ProductIndex> sf) {
    assert incomplete.isArg() : "frame incomplete not implemented yet";
    if (incomplete.isFrame()) {
      Log.debug("incomplete FI");
      //      Span t = frames.incomplete.t;
      //      for (Frame f : prunedFIs.get(t)) {
      //        RILL args2 = null;
      //        FI fi2 = new FI(f, t, args2);
      //        push(beam, copy(new FILL(fi2, frames), f(AT.COMPLETE_F, fi2, sf)));
      //      }
      throw new RuntimeException("implement me");
    } else {
      FILL fill = incomplete.fill;
      FI fi = fill.item;
      if (incomplete.missingArgSpan()) {
        // Loop over s
//        int t = -1; // TODO have t:Span need t:int
//        for (Span s : info.prunedSpans.getPossibleArgs(t)) {
        Set<Span> uniq = new HashSet<>();
        for (Span s : info.getPossibleArgs(fi)) {
          assert uniq.add(s);

          BigInteger sig = BigInteger.valueOf(info.primes.get(fi.t, fi.f, incomplete.ri.k, s));
          RI newArg = new RI(incomplete.ri.k, incomplete.ri.q, s, sig);
          Log.debug("incomplete RI - span " + newArg);

          Adjoints feats = sum(f(AT.COMPLETE_S, fi, newArg, sf), score);
          FI newFI = fi.prependArg(newArg);
          push(beam, overall, this.surgery(fill, newFI, null, feats));
        }
      } else if (incomplete.missingArgRole()) {
        // Loop over k
        int K = fi.f.numRoles();
        for (int k = 0; k < K; k++) {
//          int q = -1; // TODO
//          int p = info.primes.get(fi.t, fi.f, k, incomplete.ri.s);
//          BigInteger sig = BigInteger.valueOf(p);
//          RI newArg = new RI(k, q, incomplete.ri.s, sig);
//          Log.debug("incomplete RI - role " + newArg);
//          Adjoints feats = sum(f(AT.COMPLETE_K, fi, newArg, sf), score);
//          RILL tail = incomplete.fi.args;
//          RILL newArgs = new RILL(fi, newArg, tail);
//          FILL newFrames = new FILL(new FI(fi.f, fi.t, newArgs).withGold(fi.goldFI), frames.next);
//          push(beam, overall, new State(newFrames, noMoreFrames, noMoreTargets, null, feats, info));
          assert false : "copy surgery code from above";
        }
      } else {
        throw new RuntimeException();
      }
    }
  }

  /**
   * Add all successor states to the given beam.
   * @param beam is the beam which stores States to expand at the next iteration.
   * @param overall is the beam which stores the argmax over *all* values throughout search.
   */
  public void next(Beam beam, Beam overall) {

    Log.debug("Starting to compute next()");
    System.out.println(this.show());

    /*
     * Note: The bounds on exiting beam search early also depend on the search
     * coefficients. Importantly, the oracle needs to be able to find a good
     * trajectory, which may lead it to do costly things (like add a role to
     * a FILL which is deep down the list -- requires deep surgery).
     */

    if (noMoreFrames) {
      Log.debug("stopping due to frames.noMoreFrames");
      return;
    }

    // TODO Compute these in the constructor?
    // Or have some other way by which we can share state features between
    // the pre- and post-incomplete states?
    final List<ProductIndex> sf = getStateFeatures();

    if (incomplete != null) {
      nextComplete(beam, overall, sf);
      return;
    }

    assert incomplete == null : "we should have handled that by this point";

    if (info.config.frameMode != FrameActionTransitionSystem.ASSUME_FRAMES_ARE_GIVEN) {
      // STOP (t,f) actions
      Log.debug("adding NO_MORE_FRAMES");
      push(beam, overall, noMoreFrames(f(AT.STOP_TF, sf)));

      if (!noMoreTargets) {
        Log.debug("adding NO_MORE_TARGETS");
        push(beam, overall, noMoreTargets(f(AT.STOP_T, sf)));
      }

      // NEW (t,f) actions
      fj.data.Set<Span> tsf = frames == null
          ? fj.data.Set.<Span>empty(Ord.comparableOrd()) : frames.targetsSelectedSoFar;
      for (Span t : info.prunedFIs.keySet()) {
        if (tsf.member(t))
          continue;
        switch (info.config.frameMode) {
        case TARGET_FIRST:
          // (t,)
          if (!noMoreTargets) {
            Log.debug("adding new (t,) at " + t.shortString());
            FI fi = new FI(null, t, null).withGold(null);
            Adjoints feats = sum(this.score, f(AT.NEW_T, fi, sf));
            Incomplete inc = new Incomplete(frames);
            push(beam, overall, new State(new FILL(fi, frames), noMoreFrames, noMoreTargets, inc, feats, info));
          }
          break;
        case ONE_STEP:
          // (t,f)
          assert !noMoreFrames;
          for (Frame f : info.prunedFIs.get(t)) {
            Log.debug("adding new (t,f) at " + t.shortString() + "\t" + f.getName());
            FI fi = new FI(f, t, null).withGold(null);
            Adjoints feats = sum(this.score, f(AT.NEW_TF, fi, sf));
            Incomplete inc = null;
            push(beam, overall, new State(new FILL(fi, frames), noMoreFrames, noMoreTargets, inc, feats, info));
          }
          break;
        default:
          throw new RuntimeException("implement me: " + info.config.frameMode);
        }
      }
    }

    if (firstNotDone == null)
      firstNotDone = frames;

    // NEW (k,s) actions
    int fiProcessed = 0;
    for (FILL cur = firstNotDone;
        cur != null && !(info.config.frameByFrame && fiProcessed > 0);
        cur = cur.next) {

      FI fi = cur.item;
      Log.debug("generating args for " + fi + " (" + cur.numFrameInstances + ")");
      System.out.println();
      assert fi.t != null;

      // Auto-bump the first not-done index
      if (fiProcessed == 0) {
        if (cur != firstNotDone)
          Log.debug("bumping firstNotDone");
        firstNotDone = cur;
      }

      ArgActionTransitionSystem am = info.config.argMode;
      if (fi.noMoreArgRoles
          && (am == ArgActionTransitionSystem.ROLE_BY_ROLE
          || am == ArgActionTransitionSystem.ROLE_FIRST)) {
        Log.debug("no more args are allowed because: fi.noMoreArgRoles");
        continue;
      }

      if (fi.noMoreArgSpans
          && (am == ArgActionTransitionSystem.SPAN_BY_SPAN
          || am == ArgActionTransitionSystem.SPAN_FIRST)) {
        Log.debug("no more args are allowed because: fi.noMoreArgSpans");
        continue;
      }

      if (fi.noMoreArgRoles && fi.noMoreArgSpans) {
        Log.debug("no more args are allowed because: fi.noMoreArgRoles && fi.noMoreArgSpans");
        continue;
      }

      // Step 1 of actions that generate (k,s)
      int step1Pushed = 0;
      int K;
      switch (info.config.argMode) {
      case ROLE_FIRST:
      case ROLE_BY_ROLE:
        // Loop over k
        K = fi.f.numRoles();
        for (int k = 0; k < K; k++) {
          if (info.config.oneKperS && fi.argHasAppeared(k)) {
            Log.debug("skipping k=" + k);
            continue;
          }

          int q = -1;   // TODO
          RI newRI = new RI(k, q, null, null);

          // NEW
          Log.debug("adding new (k,?) k=" + k + "\t" + fi + "\t" + newRI);
          Adjoints featsN = sum(f(AT.NEW_K, fi, newRI, sf), score);
          State st = new State(frames, noMoreFrames, noMoreTargets, new Incomplete(cur, newRI), featsN, info);
          push(beam, overall, st);

          // STOP
          Log.debug("adding noMoreArgRoles for k=" + k + "\t" + fi + "\t" + newRI);
          int p = info.primes.get(fi.t, fi.f, k, Span.nullSpan);
          RI riStop = new RI(k, q, Span.nullSpan, BigInteger.valueOf(p));
          Adjoints featsS = sum(f(AT.STOP_K, fi, newRI, sf), score);
          Incomplete incS = null;   // Stop doesn't need a completion
          push(beam, overall, this.surgery(cur, fi.prependArg(riStop), incS, featsS));

          step1Pushed += 2;

          if (info.config.argMode == ArgActionTransitionSystem.ROLE_BY_ROLE)
            break;
        }
        break;
      case SPAN_FIRST:
      case SPAN_BY_SPAN:
        // Loop over s
        for (Span s : info.getPossibleArgs(fi)) {
          if (info.config.oneKperS && fi.spanHasAppeared(s)) {
            Log.debug("skipping s=" + s.shortString());
            continue;
          }

          RI newRI = new RI(-1, -1, s, null);

          // NEW
          Log.debug("adding new (?,s) s=" + s.shortString() + "\t" + fi + "\t" + newRI);
          Adjoints featsN = sum(f(AT.NEW_S, fi, newRI, sf), score);
          State st = new State(frames, noMoreFrames, noMoreTargets, new Incomplete(cur, newRI), featsN, info);
          push(beam, overall, st);

          // STOP
          Log.debug("adding noMoreArgRoles for s=" + s.shortString() + "\t" + fi + "\t" + newRI);
          Adjoints featsS = sum(f(AT.STOP_S, fi, newRI, sf), score);
          Incomplete incS = null;   // Stop doesn't need a completion
          push(beam, overall, this.surgery(cur, fi.noMoreArgSpans(), incS, featsS));

          // This is broken: I need to accumulate this span in realizedSpans, but with some signifier
          // to the decoder that this should not be addded to any particular k.
          if (true)
            throw new RuntimeException("figure out how to have something like nullRole...");

          step1Pushed += 2;

          if (info.config.argMode == ArgActionTransitionSystem.SPAN_BY_SPAN)
            break;
        }
        break;
      case ONE_STEP:
        assert !fi.noMoreArgRoles;
        assert !fi.noMoreArgSpans;
        // Loop over (k,s)
        K = fi.f.numRoles();
        for (Span s : info.getPossibleArgs(fi)) {
          if (info.config.oneKperS && fi.spanHasAppeared(s))
            continue;
          for (int k = 0; k < K; k++) {
            if (info.config.oneSperK && fi.argHasAppeared(k))
              continue;

            int q = -1;   // TODO
            int p = info.primes.get(fi.t, fi.f, k, s);
            RI newRI = new RI(k, q, s, BigInteger.valueOf(p));
            Incomplete inc = null;  // ONE_STEP doesn't need a completion

            // NEW
            Log.debug("adding new (k,s) k=" + k + " s=" + s.shortString() + "\t" + fi + "\t" + newRI);
            Adjoints featsN = sum(f(AT.NEW_KS, fi, newRI, sf), score);
            push(beam, overall, this.surgery(cur, fi.prependArg(newRI), inc, featsN));

            // STOP
            Log.debug("adding noMoreArgRoles for k=" + k + " s=" + s.shortString() + "\t" + fi + "\t" + newRI);
            Adjoints featsS = sum(f(AT.STOP_KS, fi, newRI, sf), score);
//            push(beam, overall, this.surgery(cur, fi.noMoreArgRoles(), inc, featsS));

            step1Pushed += 2;

            // See related complaint above
            if (true)
              throw new RuntimeException("figure out how to have something like nullRole...");
          }
        }
        break;
      default:
        throw new RuntimeException("implement me: " + info.config.argMode);
      }

      Log.debug("step1Pushed=" + step1Pushed + " fiProcessed=" + fiProcessed);
      if (step1Pushed > 0)
        fiProcessed++;

    } // END FRAME LOOP

  }

  /** Stores a pointer to a node in the tree which is incomplete */
  public class Incomplete {
    // Don't need FILL b/c there is one per State
//    public final FI fi;
    // Do need FILL over FI since I need to be able to do surgery using this
    public final FILL fill;
    // Don't need RILL b/c there is one per FI
    public final RI ri;

    public Incomplete(FILL fi) {
      this.fill = fi;
      this.ri = null;
    }

    public Incomplete(FILL fi, RI ri) {
      this.fill = fi;
      this.ri = ri;
    }

    public boolean isFrame() {
      return ri == null;
    }

    public boolean isArg() {
      return ri != null;
    }

    public boolean missingArgSpan() {
      return ri != null && ri.s == null;
    }

    public boolean missingArgRole() {
      return ri != null && ri.k < 0;
    }

    @Override
    public String toString() {
      return "(Incomplete fi=" + fill.item + " ri=" + ri + ")";
    }

    public BigInteger getSig() {
      FI fi = fill.item;
      int p;
      if (isFrame())
        p = info.primes.get(fi.t);
      else if (missingArgRole())
        p = info.primes.get(fi.t, fi.f, ri.s);
      else if (missingArgSpan())
        p = info.primes.get(fi.t, fi.f, ri.k);
      else
        throw new RuntimeException();
      return BigInteger.valueOf(p);
    }
  }

  public static class ProductIndexAdjoints implements Adjoints {
    private int[] featIdx;
    private LazyL2UpdateVector weights;
    private double l2Reg;
    private double lr;

    public ProductIndexAdjoints(double learningRate, double l2Reg, int dimension, List<ProductIndex> features, LazyL2UpdateVector weights) {
      this.lr = learningRate;
      this.l2Reg = l2Reg;
      this.weights = weights;
      this.featIdx = new int[features.size()];
      for (int i = 0; i < featIdx.length; i++)
        featIdx[i] = features.get(i).getProdFeatureModulo(dimension);
    }

    @Override
    public double forwards() {
      double d = 0;
      for (int i = 0; i < featIdx.length; i++)
        d += weights.weights.get(featIdx[i]);
      return d;
    }

    @Override
    public void backwards(double dErr_dForwards) {
      double a = lr * -dErr_dForwards;
      for (int i = 0; i < featIdx.length; i++)
        weights.weights.add(featIdx[i], a);
      weights.maybeApplyL2Reg(l2Reg);
    }
  }

  public static abstract class WeightsMatrix<T> {
    private LazyL2UpdateVector[] at2w;
    private int dimension;
    private double l2Lambda;
    private double learningRate;

    public WeightsMatrix() {
      this(1 << 22, 32, 1e-6, 0.05);
    }

    public WeightsMatrix(int dimension, int updateInterval, double l2Lambda, double learningRate) {
      this.l2Lambda = l2Lambda;
      this.learningRate = learningRate;
      this.dimension = dimension;
      int N = numRows();
      this.at2w = new LazyL2UpdateVector[N];
      for (int i = 0; i < N; i++)
        this.at2w[i] = new LazyL2UpdateVector(new IntDoubleDenseVector(dimension), updateInterval);
    }

    public abstract int numRows();

    public abstract int row(T t);

    public Adjoints getScore(T t, List<ProductIndex> features) {
      final LazyL2UpdateVector w = at2w[row(t)];
      return new ProductIndexAdjoints(learningRate, l2Lambda, dimension, features, w);
    }
  }

  public static class Weights extends WeightsMatrix<AT> {
    @Override public int numRows() { return AT.values().length; }
    @Override public int row(AT t) { return t.ordinal(); }
  }

  public static abstract class AbstractStaticFeatureCache
      extends WeightsMatrix<FeatType>
      implements StaticFeatureCache {

    @Override public int numRows() { return FeatType.values().length; }
    @Override public int row(FeatType t) { return t.ordinal(); }

    public Adjoints scoreT(Span t) {
      return getScore(FeatType.T, featT(t));
    }
    public Adjoints scoreTF(Span t, Frame f) {
      return getScore(FeatType.TF, featTF(t, f));
    }
    public Adjoints scoreTS(Span t, Span s) {
      return getScore(FeatType.TS, featTS(t, s));
    }
    public Adjoints scoreFK(Frame f, int k, int q) {
      return getScore(FeatType.FK, featFK(f, k, q));
    }
    public Adjoints scoreFKS(Frame f, int k, int q, Span s) {
      return getScore(FeatType.FKS, featFKS(f, k, q, s));
    }
    public Adjoints scoreTFKS(Span t, Frame f, int k, int q, Span s) {
      return getScore(FeatType.TFKS, featTFKS(t, f, k, q, s));
    }
  }

  // TODO make a real one :)
  public static class RandStaticFeatureCache extends AbstractStaticFeatureCache {
    private Random rand = new Random(9001);
    private int D = 9001;
    @Override
    public List<ProductIndex> featT(Span t) {
      return Arrays.asList(new ProductIndex(rand.nextInt(D), D));
    }
    @Override
    public List<ProductIndex> featTF(Span t, Frame f) {
      return Arrays.asList(new ProductIndex(rand.nextInt(D), D));
    }
    @Override
    public List<ProductIndex> featTS(Span t, Span s) {
      return Arrays.asList(new ProductIndex(rand.nextInt(D), D));
    }
    @Override
    public List<ProductIndex> featFK(Frame f, int k, int q) {
      return Arrays.asList(new ProductIndex(rand.nextInt(D), D));
    }
    @Override
    public List<ProductIndex> featFKS(Frame f, int k, int q, Span s) {
      return Arrays.asList(new ProductIndex(rand.nextInt(D), D));
    }
    @Override
    public List<ProductIndex> featTFKS(Span t, Frame f, int k, int q, Span s) {
      return Arrays.asList(new ProductIndex(rand.nextInt(D), D));
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

  public static enum FeatType {
    T, TF, TS, FK, FKS, TFKS;
  };

  public static final Comparator<State> BY_SCORE_DESC = new Comparator<State>() {
    @Override
    public int compare(State o1, State o2) {
      double s1 = o1.score.forwards();
      double s2 = o2.score.forwards();
      if (s1 > s2)
        return -1;
      if (s1 < s2)
        return +1;
      return 0;
    }
  };

  enum SpecialFrame {
    NO_MORE_FRAMES,
    NO_MORE_TARGETS,
  };

  enum SpecialRole {
//    NO_MORE_ARGS,
    NO_MORE_ARG_SPANS,
    NO_MORE_ARG_ROLES,
  };

  /**
   * Finds an index for every sub-set of (t,f,k,s) assignments via hashing.
   * There will be collisions either way, and I need something working now!
   */
  public static class PrimesAdapter {
    private Primes primes;
    private int nPrimes;
    private FrameRolePacking frp;

    // Optimization for assiging small primes:
    // As (t,f,k,s) come in, they are hashed, then looked up through this
    // map to get something in [0,n), which then map onto the first (smallest)
    // n primes, making signatures smaller/faster.
//    private IntIntHashMap hash2dense;
    private LongIntHashMap hash2dense;

    private static final int MISSING = -1;

    public PrimesAdapter(Primes p, FrameRolePacking frp) {
      this.primes = p;
      this.nPrimes = p.size();
      this.frp = frp;
//      this.hash2dense = new IntIntHashMap(30 * 10 * 5 * 30, MISSING);
      this.hash2dense = new LongIntHashMap(30 * 10 * 5 * 30, MISSING);
    }

    private int gp(long h) {
      assert h >= 0;
//      return primes.get(h % nPrimes);
      int hd = hash2dense.get(h);
      if (hd == MISSING) {
        hd = hash2dense.size();
        hash2dense.put(h, hd);
      }
      return primes.get(hd % nPrimes);
    }

    public int get(Span t) {
      return gp(17 * index(t));
    }

    public int getSpecial(SpecialFrame f) {
      return gp(13 * (1 + f.ordinal()));
    }

    public int get(Span t, Frame f) {
      return gp(11 * index(t) * (f.getId() + 1));
    }

    public int getSpecial(Span t, Frame f, SpecialRole r) {
      return gp(7 * index(t) * (1 + f.getId()) * (1 + r.ordinal()));
    }

    public int get(Span t, Frame f, int k) {
      return gp(5 * index(t) * (1 + frp.index(f, k)));
    }

    public int get(Span t, Frame f, Span s) {
      return gp(3 * index(t) * (1 + f.getId()) * index(s));
    }

    public int get(Span t, Frame f, int k, Span s) {
      return gp(2 * index(t) * (1 + frp.index(f, k)) * index(s));
    }

    private static long index(Span span) {
      if (span == Span.nullSpan)
        return 1;
      return 2 + Span.index(span);
    }
  }

//  public static class PrimesAdapter {
//    private Primes primes;
//    private FrameRolePacking frp;
//    private int specialK;
//    private int specialF;
//    private int nn;
//    private int D, K;
//
//    public PrimesAdapter(Primes p, int sentenceLength, FrameRolePacking frp) {
//      this.nn = 1 + sentenceLength * (sentenceLength - 1) / 2;
//      this.specialF = SpecialFrame.values().length;
//      this.specialK = SpecialRole.values().length;
//      this.primes = p;
//      this.frp = frp;
//      this.D = p.size() - specialF;
//      this.K = specialK + frp.size();
//    }
//
//    public int getSpecialFI(SpecialFrame f) {
//      return primes.get(f.ordinal());
//    }
//
//    // I need primes for (t,?), (t,f,k,?), and (t,f,?,s)
//    // (t,f,k,?) => use s=nullSpan?
//    // (t,f,?,s) => use k=K, or some other special value?
//    // (t,?,?,?) => use f=0,k=0,s=nullSpan
//    public int get(Span t) {
//      throw new RuntimeException("implement me");
//    }
//    public int get(Span t, Frame f) {
//      throw new RuntimeException("implement me");
//    }
//    public int get(Span t, Frame f, int k) {
//      return get(t, f, k, Span.nullSpan);
//    }
//    public int get(Span t, Frame f, Span s) {
//      throw new RuntimeException("implement me");
//    }
//
//    public int getSpecialRI(Span t, Frame f, SpecialRole r) {
//      int k = r.ordinal();
//      int i = ProductIndex.NIL
//          .prod(Span.index(t), nn)
//          .prod(Span.index(Span.nullSpan), nn)
//          .prod(k, K)
//          .getProdFeatureModulo(D);
//      i += specialF;
//      return primes.get(i);
//    }
//
//    public int get(Span t, Frame f, int kk, Span s) {
//      // Make room for:
//      // i=0: NO_MORE_FRAMES
//      // i=1: NO_MORE_TARGETS
//      // NOTE: This is different than NO_MORE_ARG* in that we hash into fewer
//      // buckets rather than add to K. This is because these values don't depend
//      // on (t,f), so there is no reason to product them into anything.
//      // Make room for:
//      // k=0: NO_MORE_ARGS
//      // k=1: NO_MORE_ARG_SPANS
//      // k=2: NO_MORE_ARG_ROLES
//      int k = specialK + frp.index(f, kk);
//      int i = ProductIndex.NIL
//          .prod(Span.index(t), nn)
//          .prod(Span.index(s), nn)
//          .prod(k, K)
//          .getProdFeatureModulo(D);
//      i += 2;
//
//      return primes.get(i);
//    }
//  }

  public static FNParse getParse(ExperimentProperties config) {
    File cache = new File("/tmp/fnparse.example");
    FNParse y;
    if (cache.isFile()) {
      Log.info("loading FNParse from disk: " + cache.getPath());
      y = (FNParse) FileUtil.deserialize(cache);
    } else {
      Log.info("computing FNParse to cache...");

      // Get the fnparse
      ItemProvider trainAndDev = new ItemProvider.ParseWrapper(DataUtil.iter2list(
          FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences()));
      double propDev = 0.2;
      int maxDev = 1000;
      ItemProvider.TrainTestSplit foo =
          new ItemProvider.TrainTestSplit(trainAndDev, propDev, maxDev, new Random(9001));
      ItemProvider train = foo.getTrain();
      //    ItemProvider dev = foo.getTest();
      //    FNParse y = FileFrameInstanceProvider.fn15trainFIP.getParsedSentences().next();
      y = train.label(1);
      Sentence s = y.getSentence();

      // Add the dparse
      ConcreteStanfordWrapper csw = ConcreteStanfordWrapper.getSingleton(false);
      Function<Sentence, DependencyParse> dParser = csw::getBasicDParse;
      Function<Sentence, ConstituencyParse> cParser = csw::getCParse;
      s.setBasicDeps(dParser.apply(s));
      s.setStanfordParse(cParser.apply(s));

      // Save to disk
      Log.info("saving FNParse to disk: " + cache.getPath());
      FileUtil.serialize(y, cache);
    }

    return y;
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);

    FNParse y = getParse(config);
    Log.info(Describe.fnParse(y));

    FrameRolePacking frp = new FrameRolePacking(FrameIndex.getFrameNet());
    Info inf = new Info();
    inf.rand = new Random(9001);
    inf.coefModelScore = 0;
    inf.coefLoss = -10;
    inf.coefRand = 1;
    inf.frPacking = frp;
    inf.config = Config.FAST_SETTINGS;
    inf.primes = new PrimesAdapter(new Primes(config), frp);
    inf.label = new LabelIndex(y);
    inf.sentence = y.getSentence();
    inf.weights = new Weights();
    inf.staticFeatureCache = new RandStaticFeatureCache();

    inf.setTargetPruningToGoldLabels();
    boolean addSpansIfMissing = true;   // for train at least
    inf.setArgPruningUsingSyntax(
        DeterministicRolePruning.Mode.XUE_PALMER_DEP_HERMANN, addSpansIfMissing);

    State s0 = new State(null, false, false, null, Adjoints.Constant.ZERO, inf)
        .setFramesToGoldLabels();

    int beamSize = 1;
    Beam.DoubleBeam cur = new Beam.DoubleBeam(beamSize);
    Beam.DoubleBeam next = new Beam.DoubleBeam(beamSize);
    Beam.DoubleBeam all = new Beam.DoubleBeam(256);
    push(next, all, s0);
    for (int i = 0; true; i++) {
      Log.debug("starting iter=" + i);
      Beam.DoubleBeam t = cur; cur = next; next = t;
      assert next.size() == 0;
      while (cur.size() > 0) {
        State s = cur.pop();
        s.next(next, all);
      }
      Log.info("collapseRate=" + next.getCollapseRate());
      if (next.size() == 0) {
        // How to keep track of best overall?
        break;
      }
    }
    System.out.println(all.size());
    System.out.println(all.getCollapseRate());
    System.out.println(all.getNumOffers());
  }


}
