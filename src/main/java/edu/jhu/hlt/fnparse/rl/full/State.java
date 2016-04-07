package edu.jhu.hlt.fnparse.rl.full;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.propbank.PropbankContRefRoleFNParseConverter;
import edu.jhu.hlt.fnparse.data.propbank.RoleType;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameArgInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.TemplateDescriptionParsingException;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full.Config.ArgActionTransitionSystem;
import edu.jhu.hlt.fnparse.rl.full.Config.FrameActionTransitionSystem;
import edu.jhu.hlt.fnparse.rl.full.weights.ProductIndexAdjoints;
import edu.jhu.hlt.fnparse.rl.full.weights.WeightsPerActionType;
import edu.jhu.hlt.fnparse.rl.full2.FNParseTransitionScheme;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker.Update;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.FNDiff;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.map.IntDoubleEntry;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;
import edu.jhu.util.Alphabet;
import fj.Ord;

public class State implements StateLike {

  public static boolean DEBUG = false;
  public static boolean DEBUG_F = false;
  public static boolean CHEAT_FEATURES_1 = true;  // if true, use the most obviuos cheating features
  public static int NUM_NEXT_EVALS = 0;

  // For prototyping
  public static final Alphabet<String> ALPH = new Alphabet<>();
  public static int ALPH_DIM = 250_000;
  public static double ALPH_DIM_GROW_RATE = 4;

  public static enum SpecialFrame {
    NO_MORE_FRAMES,
    NO_MORE_TARGETS,
  }

  public static enum SpecialRole {
    NO_MORE_ARG_SPANS,
    NO_MORE_ARG_ROLES,
  }

  public static enum FeatType {
    T, TF, TS, FK, FKS, TFKS;
  }

  /**
   * Does not have any features:Adjoints because this class is static and does
   * not have access to State.staticFeatureCache, see RILL's constructor.
   */
  public static final class RI {
    public final int k;   // k=-1 means only this span has been chosen, but it hasn't been labeled yet
    public final RoleType q;
    public final Span s;
    public final BigInteger sig;

    public RI(int k, RoleType q, Span s, BigInteger sig) {
      this.k = k;
      this.q = q;
      this.s = s;
      this.sig  = sig;
    }

    @Override
    public String toString() {
      String ss = s == null ? "null" : s.shortString();
      return "(RI k=" + k + " q=" + q + " s=" + ss + " sig=" + sig + ")";
    }
  }

  public static Adjoints sum1(Adjoints partialScore, Adjoints prefixScore) {
    return new Adjoints.Sum(partialScore, new Adjoints.OnlyShowScore("Prefix", prefixScore));
  }
  public static Adjoints sum2(Adjoints maybeNull, Adjoints notNull) {
    if (notNull == null)
      throw new IllegalArgumentException();
    if (maybeNull == null)
      return notNull;
    return new Adjoints.Sum(maybeNull, notNull);
  }
  public static Adjoints sum3(Adjoints maybeNull, Adjoints alsoMaybeNull) {
    if (maybeNull == null && alsoMaybeNull != null)
      return alsoMaybeNull;
    if (maybeNull != null && alsoMaybeNull == null)
      return maybeNull;
    if (maybeNull != null && alsoMaybeNull != null)
      return new Adjoints.Sum(maybeNull, alsoMaybeNull);
    throw new IllegalArgumentException("at least one must be non-null!");
  }

  public final class RILL {

    public final RI item;
    public final RILL next;

    // Caches which should be updateable in O(1) which may inform/prune next() or global features
    public final long realizedRoles;        // z=1
    public final long realizedRolesCont;
    public final long realizedRolesRef;
    public final long fixedRoles;           // z=1 | z=0
    public final long fixedRolesCont;
    public final long fixedRolesRef;

    // TODO See LazySet in Node code?
    // Does not count nullSpan
    public final fj.data.Set<Span> realizedSpans;   // z=1

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
      final long realizedMask;
      final long fixedMask;
      if (r.k >= 0) {
        assert r.k < 64;
        if (r.s != null && r.s != Span.nullSpan) {
          realizedMask = 1l << r.k;
        } else {
          realizedMask = 0;
        }
        fixedMask = 1l << r.k;
      } else {
        realizedMask = 0;
        fixedMask = 0;
      }
      if (l == null) {
        realizedRoles = realizedMask;
        realizedRolesCont = (r.q == RoleType.CONT ? realizedMask : 0);
        realizedRolesRef = (r.q == RoleType.REF ? realizedMask : 0);
        fixedRoles = fixedMask;
        fixedRolesCont = (r.q == RoleType.CONT ? fixedMask : 0);
        fixedRolesRef = (r.q == RoleType.REF ? fixedMask : 0);
        if (r.s == null) {
          realizedSpans = fj.data.Set.<Span>empty(Ord.comparableOrd());
        } else {
          realizedSpans = fj.data.Set.single(Ord.comparableOrd(), r.s);
        }
        sig = r.sig;
      } else {
        realizedRoles = l.realizedRoles | realizedMask;
        realizedRolesCont = l.realizedRolesCont | (r.q == RoleType.CONT ? realizedMask : 0);
        realizedRolesRef = l.realizedRolesRef | (r.q == RoleType.REF ? realizedMask : 0);
        fixedRoles = l.fixedRoles | fixedMask;
        fixedRolesCont = l.fixedRolesCont | (r.q == RoleType.CONT ? fixedMask : 0);
        fixedRolesRef = l.fixedRolesRef | (r.q == RoleType.REF ? fixedMask : 0);
        realizedSpans = r.s == null || r.s == Span.nullSpan
            ? l.realizedSpans : l.realizedSpans.insert(r.s);
        sig = l.sig.multiply(r.sig);
      }
    }

    public int getNumRealizedArgs() {
      return Long.bitCount(realizedRoles)
          + Long.bitCount(realizedRolesCont)
          + Long.bitCount(realizedRolesRef);
    }

    public int getNumFixedArgs() {
      return Long.bitCount(fixedRoles)
          + Long.bitCount(fixedRolesCont)
          + Long.bitCount(fixedRolesRef);
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
          + " nRealized=" + getNumRealizedArgs()
          + " nFixed=" + getNumFixedArgs()
          + " nmaSpans=" + noMoreArgSpans
          + " nmaRoles=" + noMoreArgRoles
          + " numRoles=" + f.numRoles()
          + (goldFI == null ? "" : " gold=" + Describe.frameInstaceJustArgsTerse(goldFI))
          + ")";
    }

    /** Mutates, returns this */
    public FI withGold(FrameInstance fi) {
      this.goldFI = fi;
      return this;
    }

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

    public int getNumRealizedArgs() {
      if (args == null)
        return 0;
      return args.getNumRealizedArgs();
    }

    public int getNumFixedArgs() {
      if (args == null)
        return 0;
      return args.getNumFixedArgs();
    }

    public boolean argIsRealized(int k, RoleType q) {
      if (args == null)
        return false;
      switch (q) {
      case BASE:
        return (args.realizedRoles & (1l << k)) != 0;
      case REF:
        return (args.realizedRolesRef & (1l << k)) != 0;
      case CONT:
        return (args.realizedRolesCont & (1l << k)) != 0;
      default:
        throw new RuntimeException("q=" + q);
      }
    }

    public boolean argIsFixed(int k, RoleType q) {
      if (args == null)
        return false;
      switch (q) {
      case BASE:
        return (args.fixedRoles & (1l << k)) != 0;
      case REF:
        return (args.fixedRolesRef & (1l << k)) != 0;
      case CONT:
        return (args.fixedRolesCont & (1l << k)) != 0;
      default:
        throw new RuntimeException("q=" + q);
      }
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
      PrimesAdapter pa = info.config.primes;

      // Need primes for (t,f)...
      // Want to be able to tell apart the states...
      // What happens when a (t,?) is completed?
      assert t != null;
      if (f == null)
        b = b.multiply(BigInteger.valueOf(pa.get(t)));
      else
        b = b.multiply(BigInteger.valueOf(pa.get(t, f)));

      long p = 1;
      if (noMoreArgSpans)
        p *= pa.getSpecial(t, f, SpecialRole.NO_MORE_ARG_SPANS);
      if (noMoreArgRoles)
        p *= pa.getSpecial(t, f, SpecialRole.NO_MORE_ARG_ROLES);
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
//  public Adjoints score;
  public StepScores<Info> score;

  // Everything that is annoying to copy in State
  public final Info info;

  public State(FILL frames, boolean noMoreFrames, boolean noMoreTargets, Incomplete incomplete, StepScores<Info> score, Info everythingElse) {
    this.frames = frames;
    this.noMoreFrames = noMoreFrames;
    this.noMoreTargets = noMoreTargets;
    this.incomplete = incomplete;
    this.score = score;
    this.info = everythingElse;
  }

//  public State noMoreFrames(Adjoints partialScore) {
  public State noMoreFrames(Object partialScore) {
    assert !noMoreFrames;
    double rand = info.config.rand.nextGaussian();
//    StepScores<Info> ss = new StepScores<>(info, partialScore, 0, 0, score.trueP, score.trueN, rand, score);
//    return new State(frames, true, true, incomplete, ss, info);
    throw new RuntimeException("fixme");
  }

//  public State noMoreTargets(Adjoints partialScore) {
  public State noMoreTargets(Object partialScore) {
    assert !noMoreTargets;
    double rand = info.config.rand.nextGaussian();
//    StepScores<Info> ss = new StepScores<>(info, partialScore, 0, 0, score.trueP, score.trueN, rand, score);
//    return new State(frames, noMoreFrames, true, incomplete, ss, info);
    throw new RuntimeException("fixme");
  }

  public String show() {
    StringBuilder sb = new StringBuilder();
    sb.append("(State\n");
    sb.append("  nmT=" + noMoreTargets + "\n");
    sb.append("  nmF=" + noMoreFrames + "\n");
    sb.append("  sig=" + getSig() + "\n");
    sb.append("  inc=" + incomplete + "\n");
    sb.append("  score=" + score + "\n");
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
      if (DEBUG) Log.debug("returning state features memo");
      return _stateFeaturesMemo;
    }

    if (DEBUG) Log.debug("computing state features");

    List<ProductIndex> f = new ArrayList<>();
    // TODO more features
    if (frames == null) {
      ff(f, "noFramesYet");
    } else {
      ff(f, "nFI=" + frames.numFrameInstances);
      ff(f, "nArgs[0]=" + frames.item.getNumRealizedArgs());
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
      int x = info.config.primes.getSpecial(SpecialFrame.NO_MORE_TARGETS);
      p = p.multiply(BigInteger.valueOf(x));
    }
    if (noMoreFrames) {
      int x = info.config.primes.getSpecial(SpecialFrame.NO_MORE_FRAMES);
      p = p.multiply(BigInteger.valueOf(x));
    }
    if (incomplete != null) {
      p = p.multiply(incomplete.getSig());
    }
    return p;
  }

  @Override   // for StateLike
  public BigInteger getSignature() {
    return getSig();
  }
  @Override   // for StateLike
  public StepScores<Info> getStepScores() {
    return score;
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
  public State surgery(FILL tail, FI newFrame, Incomplete newIncomplete, StepScores<Info> newStateScore) {
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
  public static void push(Beam<State> next, Beam<State> overallBestStates, State s) {
//    assert !(s.score instanceof Adjoints.Caching);
//    s.score = new Adjoints.Caching(s.score);
    if (DEBUG) {
      Log.debug("score: " + s.score);
//      Log.debug("because: " + s.score);
      System.out.println();
    }
    next.offer(s);
    overallBestStates.offer(s);
  }

  private int numPossibleKfor(FI newFI) {
    int K = newFI.f.numRoles();
    if (info.config.useContRoles)
      K += newFI.f.numRoles();
    if (info.config.useRefRoles)
      K += newFI.f.numRoles();
    return K;
  }

  /** How many ? -> 0 are we adding with this action? */
  private int deltaTrueNegatives(AT actionType, FI newFI, RI newRI) {
    if (actionType != AT.COMPLETE_K && actionType != AT.COMPLETE_S)
      throw new RuntimeException("this code only hanldes these cases");
    boolean hit = info.label.contains(newFI.t, newFI.f, newRI.k, newRI.q, newRI.s);
    int tn = 0;
    if (info.config.oneKperS) {
      tn += numPossibleKfor(newFI);
      if (!hit) {
        tn -= 2;    // 1 for the gold k and 1 for hyp (which are different since !hit)
        assert tn >= 0;
      }
    }
    if (info.config.oneSperK) {
      tn += info.getPossibleArgs(newFI).size();
      if (!hit) {
        tn -= 2;    // 1 for the gold s and 1 for hyp (which are different since !hit)
        assert tn >= 0;
      }
    }
    if (info.config.oneKperS && info.config.oneSperK) {
      // Correct double counts
      tn--;
      assert tn >= 0;
    }
    return tn;
  }

  /** Relevant to STOP actions. Doesn't contain nullSpan */
  public List<Span> getRelevantSpans(AT actionType, FI newFI, RI newRI) {
    switch (actionType) {
    case STOP_KS:
    case STOP_K:
      return info.getPossibleArgs(newFI);
    case STOP_S:
      assert newRI.s != null;
      return Arrays.asList(newRI.s);
    default:
      throw new RuntimeException("not relevant: " + actionType);
    }
  }

  /** Relevant to STOP actions */
  public List<IntPair> getRelevantRoles(AT actionType, FI newFI, RI newRI) {
    switch (actionType) {
    case STOP_KS:
    case STOP_S:
      List<IntPair> roles = new ArrayList<>();
      int K = newFI.f.numRoles();
      for (int k = 0; k < K; k++)
        roles.add(new IntPair(k, RoleType.BASE.ordinal()));
      if (info.config.useContRoles)
        for (int k = 0; k < K; k++)
          roles.add(new IntPair(k, RoleType.CONT.ordinal()));
      if (info.config.useRefRoles)
        for (int k = 0; k < K; k++)
          roles.add(new IntPair(k, RoleType.REF.ordinal()));
      return roles;
    case STOP_K:
      assert newRI.k >= 0 && newRI.q != null;
      return Arrays.asList(new IntPair(newRI.k, newRI.q.ordinal()));
    default:
      throw new RuntimeException("not relevant: " + actionType);
    }
  }

  public StepScores<Info> f(AT actionType, FI newFI, RI newRI, List<ProductIndex> stateFeats) {

    int fp = 0, fn = 0;
    int tp = 0, tn = 0;
    int possibleFN;
    boolean hit;

    switch (actionType) {

    /* (t,f) STUFF **********************************************************/
    // NOTE: Fall-through!
    case COMPLETE_F:
      assert false : "implement oneFperT and count TNs added here";
    case NEW_TF:
      assert false : "implement oneFperT and count TNs added here";
      assert newFI.t != null;
      assert newFI.f != null;
      if (info.label.contains(newFI.t, newFI.f))
        tp++;
      else
        fp++;
    case NEW_T:
      assert false : "implement oneFperT and count TNs added here";
      assert newFI.t != null;
      if (info.label.containsTarget(newFI.t))
        tp++;
      else
        fp++;
      if (DEBUG_F) Log.info("after new/complete F/TF fp=" + fp);
      break;

      // NOTE: Fall-through!
    case STOP_TF:

      assert false: "properly implement FN counting for args";
      assert false : "implement oneFperT and count TNs added here";

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
      fn += possibleFN;
      if (DEBUG_F) {
        Log.info("after STOP_TF possibleFN_before=" + tf.size()
        + " possibleFN_after=" + possibleFN
        + " fn=" + fn);
      }
    case STOP_T:

      assert false: "properly implement FN counting for args";
      assert false : "implement oneFperT and count TNs added here";

      assert false : "handle cont/ref roles!";
      assert false : "implement oneFperT and count TNs added here";

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
      fn += possibleFN;
      if (DEBUG_F) {
        Log.info("after STOP_T possibleFN_before=" + t.size()
        + " possibleFN_after=" + possibleFN
        + " fn=" + fn);
      }
      break;

    /* (k,s) STUFF **********************************************************/
    case NEW_KS:
      assert newRI.k >= 0;
      assert newRI.s != null && newRI.s != Span.nullSpan;

      // Count FPs
      hit = info.label.contains(newFI.t, newFI.f, newRI.k, newRI.q, newRI.s);
      if (hit) tp += 1; else fp += 1;
      if (DEBUG_F) Log.info("NEW_KS hit=" + hit + "\t" + newRI);

      // Count FNs
      if (info.config.oneKperS) {
        Set<FrameArgInstance> purview = info.label.get(newFI.t, newFI.f, newRI.s);
        fn += purview.size();
        if (hit) {
          assert purview.size() > 0;
          fn--;
        }
        if (DEBUG_F) Log.info("NEW_KS && oneKperS purview.size=" + purview.size());


        // Count TN
        // How many ? -> 0 are we adding with this action?

        // if NEW_KS and oneKperS, then we know that there are no other 1s in the K (row)
        // but there could be 0s...
        // but not if:
        assert info.config.argMode == ArgActionTransitionSystem.ONE_STEP : "other argModes do not have TN counting implemented";
        tn += numPossibleKfor(newFI);
        if (!hit) {
          tn -= 2;    // 1 for the gold k and 1 for hyp (which are different since !hit)
          assert tn >= 0;
        }
      }
      if (info.config.oneSperK) {
        /*
         * Then any (k,s') are not reachable!
         * By induction, we know that we haven't added any s' already, so we
         * can just find all items matching on k and assume they're set to 0.
         */
        // Account for FNs
        SetOfSets<FrameArgInstance> purview;
        if (newRI.q == RoleType.BASE) {
          // Then you're also prohibiting any non-base args
          purview = new SetOfSets<>(
              info.label.get(newFI.t, newFI.f, newRI.k, newRI.q),
              info.label.get(newFI.t, newFI.f, newRI.k, RoleType.CONT),
              info.label.get(newFI.t, newFI.f, newRI.k, RoleType.REF));
        } else {
          purview = new SetOfSets<>(info.label.get(newFI.t, newFI.f, newRI.k, newRI.q));
        }
        fn += purview.size();
        if (hit) {
          assert purview.size() > 0;
          fn--;
        }
        if (DEBUG_F) Log.info("NEW_KS && oneSperK purview.size=" + purview.size());

        assert false : "implement TN counting for NEW_KS";
      }
      if (info.config.oneKperS && info.config.oneSperK && hit) {
        /*
         * On double-counting (note the two oneXperYs are not mutually exclusive):
         * I am counting points which like in either a row or a column.
         * There is exactly one point which can lie in the row and column,
         * which must be this (t,f,k/q,s)
         */
        assert fn > 0;
        fn--;
        if (DEBUG_F) Log.info("NEW_KS && oneSperK && oneKperS accounting for double count");

        assert false : "implement TN counting for NEW_KS";
      }

      break;
    case NEW_S:
      assert newRI.s != null && newRI.s != Span.nullSpan;
      hit = info.label.contains(newFI.t, newFI.f, newRI.s);
      /*
       * Given (?,s) we may be able to prove fp++
       * But then we must discount our fp assessment in COMPLETE_K.
       *
       * NEW_S +hit => {nothing}  |||  COMPLETE_K +hit => {tp++, tn+=K-1}
       * NEW_S +hit => {nothing}  |||  COMPLETE_K -hit => {fp++, tn+=K-1}
       *
       * I really can't have confusion like this!
       * Either I need
       * 1) to give up on variation in code paths (require oneXperY all over the place)
       * 2) FORMALLY define what nodes mean.
       *
       * Lets take a stab at 2 before giving up and doing 1.
       * NEW_UNIQ means there exists a value for which z[...,thisVal,...] = 1
       * NEW_MANY means the same as NEW_UNIQ
       * COMPLETE_
       * => I can't have semantics in the actions, or at least *only* in the actions
       *  I need to be able to read off a tree + config and know what the current and future losses will be
       *  So, e.g., Don't have NEW_UNIQ, have NEW(c:uniq) or NEW(c:many)
       *  that is, represent the exclusion properties in the node rather than just the action.
       *
       * node {
       *   prefix : [value:type]
       *   value : parent.type      -- parent pointer is not allowed!
       *   type : int               -- could be represented as first child, but why not push it up here
       *   childMaybeLL : LL node   -- subtrees which contain yeses
       *   childNoLL : LL node      -- frozen/done nodes, certain z[...]=0
       *   possibleChildValues : LL this.type     -- sorted by static score, dynamic features penalize things late in this list
       *   accumulators : user defined, for features
       *   sig : BigInt
       *
       *   def rankingScore(v:this.type): Adjoints -- how to sort possibleChildValues
       *
       *   # Better to use Adjoints + a thunk for a State than do surgery every time
       *   # you want to push onto the beam (even if apply(action) is a persistent/fast operation!).
       *   def stop(): Adjoints     -- pop everything from possibleValues, push onto childNoLL
       *   def prune(): Adjoints    -- pop from possibleChildValues, push onto childNoLL
       *   def commit(): Adjoints   -- pop from possibleChildValues, push onto childMaybeLL (create new node)
       *
       *   # Note: leave nodes will be done upon construction since there are no remaining types to instantiate.
       *   # The leave's existence is valued only for its value.
       *   def done(): Boolean    -- returns true if possibleChildValues is empty, no more actions from this node
       * }
       * => So now you have this tree, where off of each node hangs 3 options (stop/prune/commit)
       *  How can you make pruning work such that you don't have to visit the whole tree?
       *  Could I make a PQ of parents of the nodes I'd like to expand, and when I pop a node and an upper bound on any action generated from a sub-node < next.worstState - cur.score => STOP
       *
       * Interaction between maxChildren/oneXperY and deltaLoss computation (particularly FNs)
       * I have the intuition that global features (soft constraint) can mimic AtMostK (hard constraints).
       * But the stark difference is that with global features, you don't need to worry about computing loss early.
       * When I was talking about why this is something worth doing over Q-learning, one of the things I said was that "pushing back loss", closer to it source, was a good thing.
       * Does AtMostK push back loss?
       * Oracle: probably not affected.
       * MostViolated: perhaps would focus more on static score over dynamic score (since AtMostK handles most of what the dynamic scoring is giving you?)
       *
       * => I think I found a practical concern for using AtMostK:
       * If you didn't have this constraint, then MostViolated would run amok!
       * It would fill out every cell in the tree/grid, which would take a long time!
       * Maybe not though, remember that MV also considers model score.
       * As soon as the model can push the score of these actions below deltaLoss, then there would be no reason for MV to choose them.
       *
       * The paper on shift-reduce coref taught me something important:
       * if you don't have to worry about loss(oracle)>0, then your life is much easier!
       *
       * I was thinking that NEW forcing an incomplete node should happen at the parent of the new node level, not the root node
       * The trouble is that having parallel outstanding NEWs probably isn't worth it on account of the fact that that doesn't update the global features which would change the scores (as opposed to doing these operations in serial)
       */
//      if (hit) tp += 1; else fp += 1;
      if (!hit) fp++;
      if (DEBUG_F) Log.info("NEW_S hit=" + hit + "\t" + newRI);
      if (info.config.oneKperS) {
        // Account for FNs: even if we're right, we can get at most one right
        Set<FrameArgInstance> purview = info.label.get(newFI.t, newFI.f, newRI.s);
        fn += purview.size();
        if (hit) {
          assert purview.size() > 0;
          fn--;
        }
        if (DEBUG_F) Log.info("NEW_S && oneKperS purview=" + purview.size());
      }
      break;
    case NEW_K:
      hit = info.label.contains(newFI.t, newFI.f, newRI.k, newRI.q);
//      if (hit) tp += 1; else fp += 1;
      if (!hit) fp++;
      if (DEBUG_F) Log.info("NEW_K hit=" + hit + "\t" + newRI);
      if (info.config.oneSperK) {
        // Account for FNs: even if we're right, we can get at most one right
        // Does this matter?
        // If we set set this constraint, then we can get at best one of the N items...
        // The place where it makes a difference is if we could mix transition
        // systems...
        SetOfSets<FrameArgInstance> purview;
        if (newRI.q == RoleType.BASE) {
          // Then you're also prohibiting any non-base args
          purview = new SetOfSets<>(
              info.label.get(newFI.t, newFI.f, newRI.k, newRI.q),
              info.label.get(newFI.t, newFI.f, newRI.k, RoleType.CONT),
              info.label.get(newFI.t, newFI.f, newRI.k, RoleType.REF));
        } else {
          purview = new SetOfSets<>(info.label.get(newFI.t, newFI.f, newRI.k, newRI.q));
        }
        fn += purview.size();
        if (hit) {
          assert purview.size() > 0;
          fn--;
        }
        if (DEBUG_F) Log.info("NEW_K && oneSperK purview=" + purview.size());
      }
      break;

    case COMPLETE_K:
      assert newRI.k >= 0;
      assert newRI.s != null && newRI.s != Span.nullSpan;
      hit = info.label.contains(newFI.t, newFI.f, newRI.k, newRI.q, newRI.s);
      if (DEBUG_F) Log.info("COMPLETE_K hit=" + hit + "\t" + newRI);
      if (hit) tp++; else fp++;
      // Any FN penalty due to oneKperS has been paid for in the NEW_S action
      tn += deltaTrueNegatives(actionType, newFI, newRI);
      break;
    case COMPLETE_S:
      assert newRI.k >= 0;
      assert newRI.s != null && newRI.s != Span.nullSpan;
      hit = info.label.contains(newFI.t, newFI.f, newRI.k, newRI.q, newRI.s);
      if (hit) tp += 1; else fp += 1;
      if (DEBUG_F) Log.info("COMPLETE_S hit=" + hit + "\t" + newRI);
      // Any FN penalty due to oneSperK has been paid for in the NEW_K action
      tn += deltaTrueNegatives(actionType, newFI, newRI);
      break;

    case STOP_KS:     // given (t,f): z_{t,f,k,s}=0
      assert info.config.argMode == ArgActionTransitionSystem.ONE_STEP;
    case STOP_S:      // forall k, given (t,f): z_{t,f,k,s}=0
    case STOP_K:      // forall s, given (t,f): z_{t,f,k,s}=0

      // a : z -> z' -- an action
      // deltaTN = | z.? & a(z).0 & y.0 |
      // deltaFN = | z.? & a(z).0 & y.1 |
      // deltaFP = | z.? & a(z).1 & y.0 |
      // deltaTP = | z.? & a(z).1 & y.1 |

      // z.? & a(z).0 for STOP_* actions is:
      // (t,f,?,?) \setminus z.1    -- NOTE: z.1 == a(z).1 for STOP_* actions
      // i.e. (K \times S) \setminus z.1
      // I go over this set when I do action generation anyway...

      assert incomplete == null;
      assert newFI.t != null && newFI.f != null;

      List<Span> relSpans = getRelevantSpans(actionType, newFI, newRI);

      Set<Pair<Span, IntPair>> sk1or0 = new HashSet<>();
      Set<Pair<Span, IntPair>> sk1 = new HashSet<>();
      for (RILL cur = newFI.args; cur != null; cur = cur.next) {
        RI r = cur.item;
        assert r.s != null && r.k >= 0 && r.q != null;
        IntPair kq = new IntPair(r.k, r.q.ordinal());
        Pair<Span, IntPair> skq = new Pair<>(r.s, kq);
        if (r.s == Span.nullSpan) {
          if (info.config.oneKperS) {
            // add all non-null spans with this kq to 1or0
            // All spans or relevant spans?
            // We can only check contains on relSpans, so it is a sound optimization to use relSpans
            for (Span s2 : relSpans)
              sk1or0.add(new Pair<>(s2, kq));
          }
          sk1or0.add(skq);    // shouldn't matter since s == nullSpan
        } else {
          sk1.add(skq);
        }
      }

      List<IntPair> relRoles = getRelevantRoles(actionType, newFI, newRI);
      int dfn = 0, dtn = 0;
      for (Span s : relSpans) {
        for (IntPair kq : relRoles) {
          Pair<Span, IntPair> sk = new Pair<>(s, kq);
          RoleType q = RoleType.values()[kq.second];
          boolean y_tfks = info.label.contains(newFI.t, newFI.f, kq.first, q, s);
          if (DEBUG_F) Log.info("y[" + s.shortString() + "," + kq.first + "," + q + "]=" + y_tfks);
          assert RoleType.BASE == RoleType.values()[RoleType.BASE.ordinal()];
          if (!sk1or0.contains(sk)) {
            // ? -> 0
            if (y_tfks)
              dfn++;
            else
              dtn++;
          }
        }
      }
      fn += dfn;
      tn += dtn;
      if (DEBUG_F) {
        Log.info(actionType + "\t" + newRI);
        Log.info("relSpans=" + relSpans);
        Log.info("relRoles=" + relRoles);
        Log.info("dfn=" + dfn + " dtn=" + dtn);
      }


      /*
       * TODO
       * I interleve the (k,?) and (k,s) actions.
       * You could fix the order over k ahead of time, then action generation would be faster.
       * You would loose the ability to use dynamic features to re-order k.
       * But maybe thats not such a bad thing...
       * [btw you could use dynamic features at the time of construction, which doesn't have to be at the start of all inference]
       *
       * This is the same trick that I do with (?,s) actions: I statically know
       * the set of s to loop over. That static list is determined independently
       * of model params, but we could imagine coming up with a list parametrically.
       *
       * If it did go that way, how would you represent this as an action?
       * What would the oracle do?
       * The best sort order would be [realized args] ++ [pruned/non-realized args]
       * The model sort order can be determined by s(k,?)
       * The oracle would interpolate between these I guess.
       */

      // Find all (k,s) present in the label but not yet the history
      /*
       * I want to count the Gold (t,f,k,s) which are
       * a) not in history already
       * b) match this STOP action
       * I need to build an index for (b) and then filter (a) by brute force.
      SetOfSets<FrameArgInstance> goldItems;
      if (actionType == AT.STOP_KS) {
        goldItems = new SetOfSets<>(
            info.label.get(newFI.t, newFI.f, newRI.k, RoleType.BASE, newRI.s),
            info.label.get(newFI.t, newFI.f, newRI.k, RoleType.REF, newRI.s),
            info.label.get(newFI.t, newFI.f, newRI.k, RoleType.CONT, newRI.s));
      } else if (actionType == AT.STOP_K) {
        goldItems = new SetOfSets<>(
            info.label.get(newFI.t, newFI.f, newRI.k, RoleType.BASE),
            info.label.get(newFI.t, newFI.f, newRI.k, RoleType.REF),
            info.label.get(newFI.t, newFI.f, newRI.k, RoleType.CONT));
      } else if (actionType == AT.STOP_S) {
        goldItems = new SetOfSets<>(
            info.label.get(newFI.t, newFI.f, newRI.s));
      } else {
        throw new RuntimeException();
      }

      Set<IntTrip> foo = new HashSet<>();
      possibleFN = goldItems.size();
      for (RILL cur = newFI.args; cur != null; cur = cur.next) {
        int k = cur.item.k;
        RoleType q = cur.item.q;
        Span s = cur.item.s;
        assert foo.add(new IntTrip(k, q.ordinal(), Span.indexMaybeNullSpan(s)))
            : "these should be unique! k=" + k + " q=" + q + " s=" + s.shortString()
            + "\n" + foo;
        if (k >= 0 && s != null) {
          assert q != null;
          int kk = LabelIndex.k(newFI.f, k, q);
          if (goldItems.contains(new FrameArgInstance(newFI.f, newFI.t, kk, s)))
            possibleFN--;
        }
      }
      if (DEBUG_F) {
        Log.info(actionType + " goldItems.size=" + goldItems.size()
        + " possibleFN=" + possibleFN
        + "\t" + newRI);
      }
      fn += possibleFN;
       */
      break;

    default:
      throw new RuntimeException("implement this type: " + actionType);
    }
    if (DEBUG_F)
      Log.info("done with loss: fp=" + fp + " fn=" + fn + " at=" + actionType);

    Adjoints model = info.config.weights.allFeatures(actionType, newFI, newRI, info.sentence, stateFeats);
    if (info.config.recallBias != 0) {
      switch (actionType) {
      case STOP_K:
      case STOP_KS:
      case STOP_S:
        model = new Adjoints.Sum(model, new Adjoints.NamedConstant("recallBias", info.config.recallBias));
        break;
      default:
        break;
      }
    }

    double rr = 2 * (info.config.rand.nextDouble() - 0.5);

//    return new StepScores<>(info, model, fp, fn, tp, tn, rr, score);
    throw new RuntimeException("fixme");
  }

  public StepScores<Info> f(AT actionType, FI newFI, List<ProductIndex> stateFeats) {
    return f(actionType, newFI, null, stateFeats);
  }

  // Only used for noMoreTargets and noMoreFrames
  public StepScores<Info> f(AT actionType, List<ProductIndex> stateFeats) {
    return f(actionType, null, null, stateFeats);
  }

  /** For prettier logs */
  public static class LossA extends Adjoints.Constant {
    private static final long serialVersionUID = 6821523594344630559L;
    private double fp, fn;
    public LossA(double fp, double fn) {
      super(100 - (fp + fn));
      assert super.forwards() >= 0;
      this.fp = fp;
      this.fn = fn;
    }
    @Override
    public String toString() {
      return String.format("(Loss fp=%.1f fn=%.1f)", fp,fn);
    }
  }

  /**
   * A set of sets, each of which is assumed to be disjoint, which is viewed
   * as a single set. The backing sets may be mutated and this should still
   * work fine.
   */
  public static class SetOfSets<T> {
    private Set<T>[] sets;

    @SafeVarargs
    public SetOfSets(Set<T>... sets) {
      this.sets = sets;
    }

    public boolean contains(T t) {
      for (Set<T> st : sets)
        if (st.contains(t))
          return true;
      return false;
    }

    /** O(n) where n is the number of sets */
    public int size() {
      int s = 0;
      for (Set<T> st : sets) s += st.size();
      return s;
    }
  }

  /**
   * AT == Action type
   * This is how actions are scored: we have State (dynamic) features
   * which are producted with the action type to get a featurized score.
   */
  public enum AT {
    STOP_T, STOP_TF,
    NEW_T, NEW_TF,
    COMPLETE_F,
    STOP_K, STOP_S, STOP_KS,
    NEW_K, NEW_S, NEW_KS,
    COMPLETE_K, // step 2 of (?,s)
    COMPLETE_S, // step 2 of (k,?)
  }

  private void nextComplete(Beam<State> beam, Beam<State> overall, List<ProductIndex> sf) {
    assert incomplete.isArg() : "frame incomplete not implemented yet";
    if (incomplete.isFrame()) {
      if (DEBUG) Log.debug("incomplete FI");
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
        for (Span s : info.getPossibleArgs(fi)) {

          BigInteger sig = BigInteger.valueOf(info.config.primes.get(fi.t, fi.f, incomplete.ri.k, incomplete.ri.q, s));
          RI newArg = new RI(incomplete.ri.k, incomplete.ri.q, s, sig);
          if (DEBUG) Log.debug("incomplete RI - span " + newArg);

          StepScores<Info> feats = f(AT.COMPLETE_S, fi, newArg, sf);
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

  /** Returns the number of items pushed onto the beam */
  private int nextK(Beam<State> beam, Beam<State> overall, FILL cur, int k, RoleType q, List<ProductIndex> sf) {
    FI fi = cur.item;

    // NEW
    RI newRI = new RI(k, q, null, null);
    if (DEBUG) Log.debug("adding new (k,?) k=" + k + " (" + fi.f.getRole(k) + ") q=" + q + "\t" + fi + "\t" + newRI);
    StepScores<Info> featsN = f(AT.NEW_K, fi, newRI, sf);
    State st = new State(frames, noMoreFrames, noMoreTargets, new Incomplete(cur, newRI), featsN, info);
    // TODO Check that this is correct and meaure speedup
//    st.firstNotDone = this.firstNotDone;
    push(beam, overall, st);

    // STOP
    if (DEBUG) Log.debug("adding noMoreArgRoles for k=" + k + " (" + fi.f.getRole(k) + ") q=" + q + "\t" + fi + "\t" + newRI);
    int p = info.config.primes.get(fi.t, fi.f, k, q, Span.nullSpan);
    RI riStop = new RI(k, q, Span.nullSpan, BigInteger.valueOf(p));
    StepScores<Info> featsS = f(AT.STOP_K, fi, riStop, sf);
    Incomplete incS = null;   // Stop doesn't need a completion
    State ss = this.surgery(cur, fi.prependArg(riStop), incS, featsS);
    // TODO How to update firstNotDone with surgery?
    push(beam, overall, ss);

//    if (!info.coefLoss.iszero()) {
//      double nl = featsN.getLoss().maxLoss();
//      double sl = featsS.getLoss().maxLoss();
//      if (nl == 0 && sl == 0) {
//        // Re-run f() so that you can see what loss it picked up
//        DEBUG_F = true;
//        f(AT.NEW_K, fi, newRI, sf);
//        f(AT.STOP_K, fi, riStop, sf);
//        Log.info(fi);
//      }
//      assert nl > 0 || sl > 0;
//    }
    Log.warn("re-implement this commented out bit!");

    return 2;
  }

  /**
   * Add all successor states to the given beam.
   * @param beam is the beam which stores States to expand at the next iteration.
   * @param overall is the beam which stores the argmax over *all* values throughout search.
   */
  public void next(Beam<State> beam, Beam<State> overall) {
    NUM_NEXT_EVALS++;

    if (DEBUG) {
      Log.debug("Starting to compute next()");
      System.out.println(this.show());
    }

    /*
     * Note: The bounds on exiting beam search early also depend on the search
     * coefficients. Importantly, the oracle needs to be able to find a good
     * trajectory, which may lead it to do costly things (like add a role to
     * a FILL which is deep down the list -- requires deep surgery).
     */

    if (noMoreFrames) {
      if (DEBUG) Log.debug("stopping due to frames.noMoreFrames");
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
      if (DEBUG) Log.debug("adding NO_MORE_FRAMES");
      push(beam, overall, noMoreFrames(f(AT.STOP_TF, sf)));

      if (!noMoreTargets) {
        if (DEBUG) Log.debug("adding NO_MORE_TARGETS");
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
            if (DEBUG) Log.debug("adding new (t,) at " + t.shortString());
            FI fi = new FI(null, t, null).withGold(null);
            StepScores<Info> feats = f(AT.NEW_T, fi, sf);
            Incomplete inc = new Incomplete(frames);
            push(beam, overall, new State(new FILL(fi, frames), noMoreFrames, noMoreTargets, inc, feats, info));
          }
          break;
        case ONE_STEP:
          // (t,f)
          assert !noMoreFrames;
          for (Frame f : info.prunedFIs.get(t)) {
            if (DEBUG) Log.debug("adding new (t,f) at " + t.shortString() + "\t" + f.getName());
            FI fi = new FI(f, t, null).withGold(null);
            StepScores<Info> feats = f(AT.NEW_TF, fi, sf);
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
      if (DEBUG) {
        Log.debug("generating args for " + fi + " (" + cur.numFrameInstances + ")");
        System.out.println();
      }
      assert fi.t != null;

      // Auto-bump the first not-done index
      if (fiProcessed == 0) {
        if (DEBUG && cur != firstNotDone)
          Log.debug("bumping firstNotDone");
        firstNotDone = cur;
      }

      ArgActionTransitionSystem am = info.config.argMode;
      if (fi.noMoreArgRoles
          && (am == ArgActionTransitionSystem.ROLE_BY_ROLE
          || am == ArgActionTransitionSystem.ROLE_FIRST)) {
        if (DEBUG) Log.debug("no more args are allowed because: fi.noMoreArgRoles");
        continue;
      }

      if (fi.noMoreArgSpans
          && (am == ArgActionTransitionSystem.SPAN_BY_SPAN
          || am == ArgActionTransitionSystem.SPAN_FIRST)) {
        if (DEBUG) Log.debug("no more args are allowed because: fi.noMoreArgSpans");
        continue;
      }

      if (fi.noMoreArgRoles && fi.noMoreArgSpans) {
        if (DEBUG) Log.debug("no more args are allowed because: fi.noMoreArgRoles && fi.noMoreArgSpans");
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

          /*
           * TODO implement firstNotDone trick in FI.
           * This will be k:int which is the first k s.t. !fi.argHasAppeared(k)
           * TODO Choosing a permutation to loop over k?
           */
          boolean baseYes = fi.argIsRealized(k, RoleType.BASE);
          boolean baseFixed = fi.argIsFixed(k, RoleType.BASE);
          assert ActionType.implies(baseYes, baseFixed);

          if (DEBUG)
            Log.info("k=" + k + " baseYes=" + baseYes + " baseFixed=" + baseFixed);

          if (baseYes && info.config.useContRoles && !fi.argIsFixed(k, RoleType.CONT)) {
            step1Pushed += nextK(beam, overall, cur, k, RoleType.CONT, sf);
            if (info.config.argMode == ArgActionTransitionSystem.ROLE_BY_ROLE) {
              if (DEBUG) Log.info("break CONT");
              break;
            }
          }

          if (baseYes && info.config.useRefRoles && !fi.argIsFixed(k, RoleType.REF)) {
            step1Pushed += nextK(beam, overall, cur, k, RoleType.REF, sf);
            if (info.config.argMode == ArgActionTransitionSystem.ROLE_BY_ROLE) {
              if (DEBUG) Log.info("break REF");
              break;
            }
          }

          if (baseYes && info.config.oneSperK) {
            if (DEBUG) Log.debug("skipping BASE role k=" + k + " " + fi.f.getRole(k));
            continue;
          }

          if (!baseFixed)
            step1Pushed += nextK(beam, overall, cur, k, RoleType.BASE, sf);

          if (step1Pushed > 0 && info.config.argMode == ArgActionTransitionSystem.ROLE_BY_ROLE) {
            if (DEBUG) Log.info("break ROLE_BY_ROLE");
            break;
          }
        }
        break;
      case SPAN_FIRST:
      case SPAN_BY_SPAN:
        // Loop over s
        for (Span s : info.getPossibleArgs(fi)) {
          if (info.config.oneKperS && fi.spanHasAppeared(s)) {
            if (DEBUG) Log.debug("skipping s=" + s.shortString());
            continue;
          }

          RI newRI = new RI(-1, null, s, null);

          // NEW
          if (DEBUG) Log.debug("adding new (?,s) s=" + s.shortString() + "\t" + fi + "\t" + newRI);
          StepScores<Info> featsN = f(AT.NEW_S, fi, newRI, sf);
          State st = new State(frames, noMoreFrames, noMoreTargets, new Incomplete(cur, newRI), featsN, info);
          push(beam, overall, st);

          // STOP
          if (DEBUG) Log.debug("adding noMoreArgRoles for s=" + s.shortString() + "\t" + fi + "\t" + newRI);
          StepScores<Info> featsS = f(AT.STOP_S, fi, newRI, sf);
          Incomplete incS = null;   // Stop doesn't need a completion
          push(beam, overall, this.surgery(cur, fi.noMoreArgSpans(), incS, featsS));

          // This is broken: I need to accumulate this span in realizedSpans, but with some signifier
          // to the decoder that this should not be addded to any particular k.
          if (true)
            throw new RuntimeException("figure out how to have something like nullRole...");

          step1Pushed += 2;

          if (true)
            throw new RuntimeException("add support for cont/ref roles");

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
          boolean hs = fi.spanHasAppeared(s);
          if (info.config.oneKperS && hs)
            continue;
          for (int k = 0; k < K; k++) {

            assert false : "handle yes/fixed, especially as it relates to cont/ref roles";
//            boolean ha = fi.argHasAppeared(k, RoleType.BASE);
//            if (info.config.oneKperS && ha)
//              continue;

            assert false : "generate cont/ref roles!";

            RoleType q = RoleType.BASE;
            int p = info.config.primes.get(fi.t, fi.f, k, q, s);
            RI newRI = new RI(k, q, s, BigInteger.valueOf(p));
            Incomplete inc = null;  // ONE_STEP doesn't need a completion

            // NEW
            if (DEBUG) Log.debug("adding new (k,s) k=" + k + " s=" + s.shortString() + "\t" + fi + "\t" + newRI);
            StepScores<Info> featsN = f(AT.NEW_KS, fi, newRI, sf);
            push(beam, overall, this.surgery(cur, fi.prependArg(newRI), inc, featsN));
            step1Pushed++;


            // STOP
//            if (DEBUG) Log.debug("adding noMoreArgRoles for k=" + k + " s=" + s.shortString() + "\t" + fi + "\t" + newRI);
//            Adjoints featsS = sum(f(AT.STOP_KS, fi, newRI, sf), score);
//            push(beam, overall, this.surgery(cur, fi.noMoreArgRoles(), inc, featsS));
//            step1Pushed++;
          }
        }
        StepScores<Info> featsS = f(AT.STOP_KS, fi, new RI(-1, null, null, null), sf);
        push(beam, overall, this.surgery(cur, fi.noMoreArgs(), null, featsS));

        if (true)
          throw new RuntimeException("add support for cont/ref roles");

        break;
      default:
        throw new RuntimeException("implement me: " + info.config.argMode);
      }

      if (DEBUG) Log.debug("step1Pushed=" + step1Pushed + " fiProcessed=" + fiProcessed);
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
        p = info.config.primes.get(fi.t);
      else if (missingArgRole())
        p = info.config.primes.get(fi.t, fi.f, ri.s);
      else if (missingArgSpan())
        p = info.config.primes.get(fi.t, fi.f, ri.k, ri.q);
      else
        throw new RuntimeException();
      return BigInteger.valueOf(p);
    }
  }

  public static final Comparator<State> BY_SCORE_DESC = new Comparator<State>() {
    @Override
    public int compare(State o1, State o2) {
//      double s1 = o1.score.forwardsMax();
//      double s2 = o2.score.forwardsMax();
//      if (s1 > s2)
//        return -1;
//      if (s1 < s2)
//        return +1;
//      return 0;
      throw new RuntimeException("fixme");
    }
  };

  /** Returns some FRAMENET parses, caches to disk */
  @SuppressWarnings("unchecked")
  public static List<FNParse> getParse() {
    File cache = new File("/tmp/fnparse.example");
    List<FNParse> ys;
    if (cache.isFile()) {
      Log.info("loading List<FNParse> from disk: " + cache.getPath());
      ys = (List<FNParse>) FileUtil.deserialize(cache);
    } else {
      Log.info("computing List<FNParse> to cache...");

      // Get the fnparse
      ItemProvider trainAndDev = new ItemProvider.ParseWrapper(DataUtil.iter2list(
          FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences()));
      double propDev = 0.2;
      int maxDev = 1000;
      ItemProvider.TrainTestSplit foo =
          new ItemProvider.TrainTestSplit(trainAndDev, propDev, maxDev, new Random(9001));
      ItemProvider train = foo.getTrain();

      ys = new ArrayList<>();
      for (int i = 0; i < 300; i++) {
        FNParse y = train.label(i);
        Sentence s = y.getSentence();

        // Add the dparse
        ConcreteStanfordWrapper csw = ConcreteStanfordWrapper.getSingleton(false);
//        Function<Sentence, DependencyParse> dParser = csw::getBasicDParse;
//        Function<Sentence, ConstituencyParse> cParser = csw::getCParse;
//        s.setBasicDeps(dParser.apply(s));
//        s.setStanfordParse(cParser.apply(s));
        csw.addAllParses(s, null, true);

        ys.add(y);
      }

      // Save to disk
      Log.info("saving List<FNParse> to disk: " + cache.getPath());
      FileUtil.serialize(ys, cache);
    }

    return ys;
  }

  /** returns true if it could add the dummy role */
  public static boolean addDummyContRefRole(FNParse y, Random r, RoleType rt) {
    if (y.numFrameInstances() == 0)
      return false;

    // Make a random span that this cont/ref role will be located at
    int n = y.getSentence().size();
    Span s = Span.randomSpan(n, r);

    // Find a realized role to cont/ref
    for (FrameInstance fi : y.getFrameInstances()) {
      int K = fi.getFrame().numRoles();
      for (int k = 0; k < K; k++) {
        if (fi.getArgument(k) != Span.nullSpan) {
          if (rt == RoleType.CONT)
            fi.addContinuationRole(k, s);
          else if (rt == RoleType.REF)
            fi.addReferenceRole(k, s);
          else
            throw new IllegalArgumentException("not cont/ref: " + rt);
          return true;
        }
      }
    }
    return false;
  }

  public static FNParse runInference2(Info inf) {
    Pair<State, DoubleBeam<State>> p = runInference(inf);
    State s = p.get1();
    FNParse y = s.decode();
    return y;
  }

  /**
   * @return 1) the state which has the highest beam search objective (e.g. use
   * this for decoding) and 2) a PQ of States which are a part of the margin
   * constraints optimization problem, i.e. ordered by
   *   f(z) = modelScore(z) + max_{y \in Proj(z)} loss(y)
   */
  public static Pair<State, DoubleBeam<State>> runInference(Info inf) {
    if (DEBUG)
      Log.info("starting: " + inf);
    /*
     * TODO maximizing loss: start with loss=0 and add in deltaLoss
     * minimizing loss: start with loss=totalLoss and subtract out deltaLoss
     */
    StepScores<Info> zero = null;// new StepScores<>(inf, Adjoints.Constant.ZERO, MaxLoss.ZERO, 0, null);
    assert false : "fixme, StepScores no longer has prev, need to do sum before StepScores constructor";
    State s0 = new State(null, false, false, null, zero, inf)
        .setFramesToGoldLabels();

    // Objective: s(z) + max_{y \in Proj(z)} loss(y)
    // [where s(z) may contain random scores]
    DoubleBeam<State> all = new DoubleBeam<>(inf.htsConstraints);

    // Objective: search objective, that is,
    // coef:      accumLoss    accumModel      accumRand
    // oracle:    -1             0              0
    // mv:        +1            +1              0
    DoubleBeam<State> cur = new DoubleBeam<>(inf.htsBeam);
    DoubleBeam<State> next = new DoubleBeam<>(inf.htsBeam);

    State lastState = null;
    push(next, all, s0);
    for (int i = 0; true; i++) {
      if (DEBUG) Log.debug("starting iter=" + i);
      DoubleBeam<State> t = cur; cur = next; next = t;
      assert next.size() == 0;
      for (int b = 0; cur.size() > 0; b++) {
        State s = cur.pop();
        if (b == 0) // best item in cur
          lastState = s;
        s.next(next, all);
      }
      if (DEBUG) Log.info("collapseRate=" + next.getCollapseRate());
      if (next.size() == 0)
        break;
    }

    assert lastState != null;
    return new Pair<>(lastState, all);
  }

  /**
   * Make sure the oracle can get 100% accuracy if search is only guided by loss.
   */
  public static void checkOracle(FNParse y, Config conf) {

    if (conf.useContRoles && y.numFrameInstances() > 0)
      addDummyContRefRole(y, conf.rand, RoleType.CONT);
    if (conf.useRefRoles && y.numFrameInstances() > 0)
      addDummyContRefRole(y, conf.rand, RoleType.REF);

    Info inf = new Info(conf);
//    inf.beamSize = 1;
    inf.config = conf;
    inf.setOracleCoefs();
    inf.setLabel(y);
    inf.setTargetPruningToGoldLabels();
    // We're assuming the FNParses already have the parses in them
    DeterministicRolePruning drp = new DeterministicRolePruning(
        DeterministicRolePruning.Mode.XUE_PALMER_DEP_HERMANN, null, null);
    boolean addSpansIfMissing = true;   // for train at least
    inf.setArgPruning(drp, addSpansIfMissing);

    FNParse yhat = runInference2(inf);

    SentenceEval se = new SentenceEval(y, yhat);
    Map<String, Double> r = BasicEvaluation.evaluate(Arrays.asList(se));
    double f1 = r.get("ArgumentMicroF1");
    if (f1 != 1) {
      DEBUG = true;
      DEBUG_F = true;
      System.out.println("re-playing oracle:");
      runInference2(inf);
      BasicEvaluation.showResults("oracle", r);
      System.out.println("\ndiffArgs:\n" + FNDiff.diffArgs(y, yhat, true));
    }
    assert f1 == 1 : "f1=" + f1;
  }

  /**
   * Make sure you can fit one example.
   */
  public static void checkLearning(FNParse y, Config conf) {
    Log.info("starting on " + y.getId() + " numFI=" + y.numFrameInstances());

    Info oracleInf = new Info(conf).setOracleCoefs();
    Info mvInf = new Info(conf).setMostViolatedCoefs();
    Info decInf = new Info(conf).setDecodeCoefs();
    for (Info inf : Arrays.asList(oracleInf, mvInf, decInf)) {
//      inf.beamSize = 1;
      inf.config = conf;
      inf.setLabel(y);
      inf.setTargetPruningToGoldLabels();

      // We're assuming the FNParses already have the parses in them
      DeterministicRolePruning drp = new DeterministicRolePruning(
          DeterministicRolePruning.Mode.XUE_PALMER_DEP_HERMANN, null, null);

      // Normally we would not allow the decoder to see the gold spans, but for
      // this test it is easier to check against F1=1 than F1=bestAchievable
      boolean addSpansIfMissing = true;// inf != decInf;
      inf.setArgPruning(drp, addSpansIfMissing);
//      System.out.println("addSpansIfMissing=" + addSpansIfMissing
//          + " prunedSpans=" + inf.prunedSpans.describe());
    }

    int numShards = 1;
    FModel fmodel = new FModel(null, DeterministicRolePruning.Mode.XUE_PALMER_DEP_HERMANN, numShards);
    fmodel.setConfig(conf);
    FNParseTransitionScheme ts = fmodel.getShardWeights(new Shard(0, numShards));

    int successesInARow = 0;
    int maxiter = 500;
    FNParse yhat = null;
    for (int i = 0; i < maxiter; i++) {

      Update u = fmodel.getUpdate(y, ts);
      u.apply(1);

      if (i % 5 == 0) {
        yhat = fmodel.predict(y);
        double f1 = f1(y, yhat);
        Log.info("iter=" + i + " f1=" + f1);
        if (f1 == 1)
          successesInARow++;
        else
          successesInARow = 0;
        if (successesInARow > 4)
          return;
      }
    }
    DEBUG = true;
    System.out.println("re-playing update for debugging:");

    System.out.println("searching for oracle update:");
    Pair<State, DoubleBeam<State>> oracleStateColl = runInference(oracleInf);
    State oracleState = oracleStateColl.get2().pop();
    System.out.println("oracleState=" + oracleState.show());

    System.out.println("searching for mv update:");
    Pair<State, DoubleBeam<State>> mvStateColl = runInference(mvInf);
    State mvState = mvStateColl.get2().pop();
    System.out.println("mvState=" + mvState.show());

    System.out.println("applying updates:");
//    oracleState.score.backwards(lr);
//    mvState.score.backwards(lr);
    if (true)
      throw new RuntimeException("fixme");

    System.out.println("re-playing inference for debugging:");
    yhat = runInference2(decInf);
    System.out.println("y=" + Describe.fnParse(y));
    System.out.println("yhat=" + Describe.fnParse(yhat));
    System.out.println("yhatPruning=" + decInf.prunedSpans.describe());

    decInf.setTargetPruningToGoldLabels();
    boolean addSpansIfMissing = true;// inf != decInf;
    // We're assuming the FNParses already have the parses in them
    DeterministicRolePruning drp = new DeterministicRolePruning(
        DeterministicRolePruning.Mode.XUE_PALMER_DEP_HERMANN, null, null);
    decInf.setArgPruning(drp, addSpansIfMissing);
    System.out.println("yhatPruning=" + decInf.prunedSpans.describe());

    assert false : "didn't learn in " + maxiter + " iterations";
  }

  private static double f1(FNParse y, FNParse yhat) {
    SentenceEval se = new SentenceEval(y, yhat);
    Map<String, Double> r = BasicEvaluation.evaluate(Arrays.asList(se));
    double f1 = r.get("ArgumentMicroF1");
    return f1;
  }

  public static void main(String[] args) throws TemplateDescriptionParsingException {
    ExperimentProperties config = ExperimentProperties.init(args);

    List<FNParse> ys = getParse();
//    Log.info(Describe.fnParse(y));

    DEBUG = false;

    Config conf = new Config();
    conf.frPacking = new FrameRolePacking(FrameIndex.getFrameNet());
    conf.primes = new PrimesAdapter(new Primes(config), conf.frPacking);

//    CachedFeatureParamsShim features = new RandomFeatures();
    CachedFeatureParamsShim features = new CheatingFeatures().add(ys);
    int updateInterval = 1;
    int dimension = 1 << 18;
    conf.weights = new GeneralizedWeights(conf, features, dimension, updateInterval);
    conf.weights.staticL2Penalty = 1e-3;
    conf.weights.debug = true;

    conf.roleDependsOnFrame = true;
//    conf.argMode = ArgActionTransitionSystem.ROLE_BY_ROLE;
    conf.argMode = ArgActionTransitionSystem.ROLE_FIRST;
//    conf.argMode = ArgActionTransitionSystem.ONE_STEP;
    conf.frameByFrame = true;

    conf.useContRoles = true;
    conf.useRefRoles = true;
    conf.rand = new Random(9001);

    boolean testCachedFeatures = false;

    Log.info("loading some parses in the background while we run other tests...");
    CachedFeatures cf = null;
    if (testCachedFeatures) {
      cf = CachedFeatures.buildCachedFeaturesForTesting(config);
      System.out.println("done loading CachedFeatures");
    }

    // Sort parses by number of frames so that small (easy to debug/see) examples come first
    Collections.sort(ys, new Comparator<FNParse>() {
      @Override
      public int compare(FNParse o1, FNParse o2) {
        return numItems(o1) - numItems(o2);
      }
    });

    // Call checkOracle
    Log.info("testing the oracle performance is perfect");
    long start = System.currentTimeMillis();
    int nParses = 0;
    for (FNParse y : ys) {
      nParses++;
      checkOracle(y, conf);
      if (DEBUG)
        break;
    }
    long time = System.currentTimeMillis() - start;

    System.out.println("took " + time + " ms, " + (nParses*1000d)/time + " parse/sec");
    System.out.println("numNextEvals: " + NUM_NEXT_EVALS);
    System.out.println("numParses: " + nParses);

    // Call checkLearning
    Log.info("checking that we can learn to mimic the oracle");
    conf.setNoGlobalFeatures();
    for (FNParse y : ys) {
      int i = numItems(y);
      if (i == 0)
        continue;
      if (i > 10)
        break;
      Log.info("checking learning on " + y.getId() + " numItems=" + i);
      checkLearning(y, conf);
    }

    // Try to get some updates using the FNParses in CachedFeatures
    if (testCachedFeatures) {
      Log.info("trying out some updates when using CachedFeatures.ItemProvider");
      int numShards = 1;
      FModel m = new FModel(null, DeterministicRolePruning.Mode.CACHED_FEATURES, numShards);
      m.setCachedFeatures(cf.params);
      FNParseTransitionScheme ts = m.getShardWeights(new Shard(0, numShards));
      CachedFeatures.ItemProvider ip = cf.new ItemProvider(100, false, false);
      Log.info("ip.loaded=" + ip.getNumActuallyLoaded());
      for (int i = 0; i < ip.size(); i++) {
        System.out.println("starting on parse " + (i+1));
        FNParse y = ip.label(i);
        System.out.println("getting update for " + y.getId());
        Update u = m.getUpdate(y, ts);
        System.out.println("applying update for " + y.getId());
        u.apply(1);
      }
    }
    System.out.println("fully done");
  }

  public static int numItems(FNParse y) {
    int h = 0;
    for (FrameInstance fi : y.getFrameInstances())
      h += fi.numRealizedArguments() + 1;
    return h;
  }

  /**
   * Returns a {@link FNParse} possibly with continuation/reference roles.
   * @see PropbankContRefRoleFNParseConverter#flatten(FNParse)
   * in order to flatten down into a representation that evaluation can handle.
   */
  public FNParse decode() {
    Sentence s = info.sentence;
    List<FrameInstance> fis = new ArrayList<>();
    for (FILL cur = frames; cur != null; cur = cur.next) {
      FI fi = cur.item;
      List<Pair<String, Span>> arguments = new ArrayList<>();
      for (RILL curr = fi.args; curr != null; curr = curr.next) {
        RI ri = curr.item;
        if (ri.s == Span.nullSpan)
          continue;
        String role = fi.f.getRole(ri.k);
        if (ri.q == RoleType.CONT)
          role = "C-" + role;
        else if (ri.q == RoleType.REF)
          role = "R-" + role;
        else
          assert ri.q == RoleType.BASE;
        if (DEBUG) System.out.println("[decode] " + role + " " + ri.s.shortString());
        arguments.add(new Pair<>(role, ri.s));
      }
      try {
        fis.add(FrameInstance.buildPropbankFrameInstance(fi.f, fi.t, arguments, s));
      } catch (Exception e) {
        for (Pair<String, Span> a : arguments)
          System.err.println(a);
        throw new RuntimeException(e);
      }
    }
    return new FNParse(s, fis);
  }


  /* Features *****************************************************************/

  public static interface CachedFeatureParamsShim {
    public IntDoubleUnsortedVector getFeatures(Sentence s, Span t, Span a);
  }
  public static class RandomFeatures implements CachedFeatureParamsShim {
    private Random rand = new Random(9001);
    public IntDoubleUnsortedVector getFeatures(Sentence s, Span t, Span a) {
      IntDoubleUnsortedVector fv = new IntDoubleUnsortedVector();
      int k = rand.nextInt(20) + 5;
      for (int i = 0; i < k; i++)
        fv.add(rand.nextInt(1024), 1);
      return fv;
    }
  }
  public static class CheatingFeatures implements CachedFeatureParamsShim {
    public int numShowLabel = 25;
    public int numShowNoise = 5;
    public int numPosFeats = 100;
    public int numNegFeats = 100;

    private Set<Pair<String, SpanPair>> inGold;
    private Random rand;

    public CheatingFeatures() {
      inGold = new HashSet<>();
      rand = new Random();
    }

    public CheatingFeatures add(FNParse y) {
      String sId = y.getSentence().getId();
      for (FrameInstance fi : y.getFrameInstances()) {
        Span t = fi.getTarget();
        int K = fi.getFrame().numRoles();
        for (int k = 0; k < K; k++) {
          Span s = fi.getArgument(k);
          if (s != Span.nullSpan)
            inGold.add(new Pair<>(sId, new SpanPair(t, s)));
          for (Span ss : fi.getContinuationRoleSpans(k))
            inGold.add(new Pair<>(sId, new SpanPair(t, ss)));
          for (Span ss : fi.getReferenceRoleSpans(k))
            inGold.add(new Pair<>(sId, new SpanPair(t, ss)));
        }
      }
      return this;
    }

    public CheatingFeatures add(Collection<FNParse> ys) {
      for (FNParse y : ys)
        add(y);
      return this;
    }

    private int countY = 0, countN = 0;
    @Override
    public IntDoubleUnsortedVector getFeatures(Sentence sent, Span t, Span s) {
      boolean y = inGold.contains(new Pair<>(sent.getId(), new SpanPair(t, s)));
      if (y) countY++; else countN++;
      if ((countY + countN) % 10000 == 0)
        Log.info("countY=" + countY + " countN=" + countN);
      IntDoubleUnsortedVector fv = new IntDoubleUnsortedVector();
      int a, b;
      if (y) {
        a = 0;
        b = numPosFeats;
      } else {
        a = numPosFeats;
        b = numPosFeats + numNegFeats;
      }
      for (int i = 0; i < numShowLabel; i++) {
        int r = b - a;
        int x = rand.nextInt(r) + a;
        fv.add(x, 1);
      }
      int r = numPosFeats + numNegFeats;
      for (int i = 0; i < numShowNoise; i++)
        fv.add(rand.nextInt(r), 1);
      return fv;
    }
  }

  /**
   * Does all feature dispatch.
   */
  public static class GeneralizedWeights {
    private Config config;
    private WeightsPerActionType globalFeatureWeights;
    private WeightsPerActionType stateFeatureWeights;

    private CachedFeatureParamsShim staticFeatures;
//    private CachedFeatures.Params staticFeatures;
    // Weights for static features are in CachedFeatures.Params
    // (f,t,s) -> at -> k -> score
    // (f,t,s) -> at -> score
    private LazyL2UpdateVector[] at2sfWeights;
//    private LazyL2UpdateVector[][] at2k2sfWeights;
    private LazyL2UpdateVector[] at2k2sfWeights;    // indexed by at, then the k*feature dimension is done via hashing
    private int[] kPrimes;    // k -> prime (starting at 1)
    private int dim;
    private int updateInterval;

    public double staticL2Penalty = 1e-7;
    public double staticLR = 1;  // relative to higher-level learning rate

    public void setStaticFeatures(CachedFeatureParamsShim f) {
      this.staticFeatures = f;
    }

    public GeneralizedWeights(Config config, CachedFeatureParamsShim staticFeats,
        int dimension, int updateInterval) {
      this.config = config;
      this.staticFeatures = staticFeats;
      this.globalFeatureWeights = new WeightsPerActionType();
      this.stateFeatureWeights = new WeightsPerActionType();

      /*
       * This is too many params!
       * D = ~1M      (features)
       * K = ~1000    (roles)
       * T = ~6       (action type)
       *
       * Ah!
       * Low-rank tensor thing sounds a little strange, I can't see a 100 dim
       * embedding of each D and each K working well (plus, thats a lot of params too)...
       *
       * Well, it used to work with DxK (at least for propbank)...
       * The dimension along which I really want to reduce is K
       *    TxK * r(D)
       * or r(TxK) * r(D)
       * Give a 64 dimensional embedding of this and you get:
       * 64 * (6 * 1000 + 1M) ~= 64 * 1M
       * If I can get that number of features down, then I can easily reduce the number of features
       * For propbank:
       * K=20, so the number of features isn't significantly different
       *
       * I can see too many things going wrong with the embeddings model...
       * probably easier if I just hash into a reasonably sized space.
       */

      this.updateInterval = updateInterval;
      dim = dimension;
      int K = config.roleDependsOnFrame ? config.frPacking.size() : 100;
//      Log.info("D=" + D + " K=" + K + " AT.size=" + AT.values().length + " all: " + (8d * D * K * AT.values().length)/(1024d * 1024d) + " MB");
      this.at2sfWeights = new LazyL2UpdateVector[AT.values().length];
      this.at2k2sfWeights = new LazyL2UpdateVector[AT.values().length];
      for (int i = 0; i < at2k2sfWeights.length; i++) {
        this.at2sfWeights[i] = new LazyL2UpdateVector(new IntDoubleDenseVector(dim), updateInterval);
        this.at2k2sfWeights[i] = new LazyL2UpdateVector(new IntDoubleDenseVector(dim), updateInterval);
      }
//      this.at2k2sfWeights = new LazyL2UpdateVector[AT.values().length][K];
//      for (int i = 0; i < at2k2sfWeights.length; i++) {
//        this.at2sfWeights[i] = new LazyL2UpdateVector(new IntDoubleDenseVector(D), updateInterval);
//        for (int k = 0; k < K; k++)
//          this.at2k2sfWeights[i][k] = new LazyL2UpdateVector(new IntDoubleDenseVector(D), updateInterval);
//      }
      Primes p = config.primes.getPrimes();
      this.kPrimes = new int[K];
      this.kPrimes[0] = 1;
      for (int i = 1; i < K; i++) {
        this.kPrimes[i] = p.get(i - 1);
        assert this.kPrimes[i-1] < this.kPrimes[i] : this.kPrimes[i-1] + " " + this.kPrimes[i] + " " + i;
      }

    }

    public void showWeightSummary() {
      for (int i = 0; i < at2sfWeights.length; i++) {
        double l2 = at2sfWeights[i].weights.getL2Norm();
        double inf = at2sfWeights[i].weights.getInfNorm();
        if (debug) {
          Log.info("i=" + i + " l2=" + l2 + " inf=" + inf);
        } else {
          double kl2 = at2k2sfWeights[i].weights.getL2Norm();
          double kinf = at2k2sfWeights[i].weights.getInfNorm();
          Log.info("i=" + i + " l2=" + l2 + " inf=" + inf + " kl2=" + kl2 + " kinf=" + kinf);
        }
      }
    }

    public static List<ProductIndex> convert(IntDoubleUnsortedVector fv, int dim) {
      List<ProductIndex> p = new ArrayList<>();
      Iterator<IntDoubleEntry> itr = fv.iterator();
      while (itr.hasNext()) {
        IntDoubleEntry i = itr.next();
//        assert i.get() == 1 : "non-binary feature: " + i.get();
        int f = i.index() % dim;
        p.add(new ProductIndex(f, dim));
      }
      return p;
    }

    private Alphabet<String> dbgAlph = new Alphabet<>();
    public boolean debug = false;
    private int fCalls = 0;

    public Adjoints allFeatures(AT actionType, FI fi, RI ri, Sentence s, List<ProductIndex> stateFeatures) {
      assert fi.f != null;
      boolean attemptApplyL2Update = true;

      if (++fCalls % 500000 == 0) {
        Log.info("debug=true, fCalls=" + fCalls + " anyGlobalFeatures=" + config.anyGlobalFeatures());
        showWeightSummary();
      }

      IntDoubleUnsortedVector f;
      if (ri.s != null) {
        f = staticFeatures.getFeatures(s, fi.t, ri.s);
      } else {
        int p = 31;
        f = new IntDoubleUnsortedVector(3);
        f.add(p, 1);
        f.add(p * config.frPacking.index(fi.f), 1);
        f.add(p * config.frPacking.index(fi.f, ri.k), 1);
      }
      LazyL2UpdateVector w = at2sfWeights[actionType.ordinal()];
      List<ProductIndex> f2 = convert(f, dim);
      Adjoints staticScore = new ProductIndexAdjoints(staticLR, staticL2Penalty, dim, f2, w, attemptApplyL2Update);

      if (ri.k >= 0) {
        int prodF, prodC;
        if (config.roleDependsOnFrame) {
          prodF = config.frPacking.index(fi.f, ri.k);
          prodC = config.frPacking.size();
        } else {
          prodF = ri.k;
          prodC = fi.f.numRoles();
        }
        List<ProductIndex> fk = new ArrayList<>(f2.size());
        for (ProductIndex p : f2) {
          ProductIndex pp = p.prod(prodF, prodC);
          fk.add(pp);
        }
        LazyL2UpdateVector wk = at2k2sfWeights[actionType.ordinal()];
        Adjoints staticScoreK = new ProductIndexAdjoints(staticLR, staticL2Penalty, dim, fk, wk, attemptApplyL2Update);
        staticScore = new Adjoints.Sum(staticScore, staticScoreK);
      }

      if (debug) {
        if (CHEAT_FEATURES_1) {
          String overFeat = fi.f.getName() + "_" + fi.t.shortString() + "_" + ri.k + "_" + ri.q + "_" + Span.safeShortString(ri.s);
          int overFeatI = dbgAlph.lookupIndex(overFeat, true);
          List<ProductIndex> overFeats = Arrays.asList(new ProductIndex(overFeatI));
          LazyL2UpdateVector ww = at2k2sfWeights[actionType.ordinal()];
          if (DEBUG)
            Log.info("overFeat=" + overFeatI + " weight=" + ww.weights.get(overFeatI) + "\t" + overFeat);
          Adjoints score = new ProductIndexAdjoints(staticLR, staticL2Penalty, dim, overFeats, ww, attemptApplyL2Update);
          return score;
        } else {
          throw new RuntimeException("implement me");
        }
      }

      List<ProductIndex> gf = globalFeatures(actionType, fi, ri);
      Adjoints gfa = globalFeatureWeights.getScore(actionType, gf);

      Adjoints sfa = stateFeatureWeights.getScore(actionType, stateFeatures);

      return new Adjoints.Sum(gfa, sfa, staticScore);
    }

    // The type of global feature.
    // (they can all be put into the same set of weights because the features are
    //  producted with the global feature type).
    public static enum GF {
      ARG_LOC,
      NUM_ARG,
      ROLE_COOC;
    }

    public static final ProductIndex ARG_LOC_I = new ProductIndex(GF.ARG_LOC.ordinal(), GF.values().length);
    public static final ProductIndex NUM_ARG_I = new ProductIndex(GF.NUM_ARG.ordinal(), GF.values().length);
    public static final ProductIndex ROLE_COOC_I = new ProductIndex(GF.ROLE_COOC.ordinal(), GF.values().length);

    public static void stack(List<ProductIndex> addTo, ProductIndex... features) {
      int n = features.length;
      for (int i = 0; i < n; i++) {
        ProductIndex pi = features[i];
        if (pi != ProductIndex.NIL)
          addTo.add(pi.prod(i, n));
      }
    }

    private List<ProductIndex> globalFeatures(AT actionType, FI fi, RI ri) {
      List<ProductIndex> feats = new ArrayList<>();

      int F = config.frPacking.getNumFrames();
      ProductIndex f = new ProductIndex(fi.f.getId(), F);

     // Handles COMMIT vs PRUNE
      if (ri.s != null)
        f = f.prod(ri.s == Span.nullSpan);
      // This branching is fine as long as s==null is the same for a given AT and the weights are AT specific

      ProductIndex fk = null;
//      ProductIndex fkq = null;
      if (ri.k >= 0) {
        fk = new ProductIndex(config.frPacking.index(fi.f, ri.k), config.frPacking.size())
            .prod(ri.s == Span.nullSpan);
//        fkq = fk.prod(ri.q.ordinal(), RoleType.values().length);
      }

      // argLoc
      if (config.argLocFeature) {
        ProductIndex gf = ARG_LOC_I;
        switch (actionType) {
        case COMPLETE_S:
        case NEW_S:
          assert ri.s != Span.nullSpan;
          Span s2 = ri.s;
          assert s2 != null;
          ProductIndex rT = BasicFeatureTemplates.spanPosRel2(fi.t, s2);
          for (RILL cur = fi.args; cur != null; cur = cur.next) {
            RI ri2 = cur.item;
            if (ri2 == ri)
              continue;
            Span s1 = ri.s;
            if (s1 == Span.nullSpan)    // TODO Build skip-list
              continue;
            ProductIndex rA = gf.flatProd(BasicFeatureTemplates.spanPosRel2(s1, s2));
            if (fk == null) {
              stack(feats, rA, rA.flatProd(rT), rA.flatProd(f), ProductIndex.NIL);
            } else {
              stack(feats, rA, rA.flatProd(rT), rA.flatProd(f), rA.flatProd(fk));
            }
          }
          break;
        default:
          break;
        }
      }

      // numArgs
      if (config.numArgsFeature) {
        ProductIndex gf = NUM_ARG_I;
        switch (actionType) {
        case NEW_K:
        case COMPLETE_K:
        case COMPLETE_S:
          int n = 8;
          int na = Math.min(n, fi.getNumRealizedArgs());
          ProductIndex nap = gf.prod(na, n);
          ProductIndex gt0 = gf.prod(na >= 1);
          ProductIndex gt1 = gf.prod(na >= 2);
          ProductIndex gt2 = gf.prod(na >= 3);
          if (fk == null) {
            stack(feats,
                nap,
                nap.flatProd(f),
                ProductIndex.NIL,
                gt0,
                gt1,
                gt2,
                gt0.flatProd(f),
                gt1.flatProd(f),
                gt2.flatProd(f),
                ProductIndex.NIL,
                ProductIndex.NIL,
                ProductIndex.NIL);
          } else {
            stack(feats,
                nap,
                nap.flatProd(f),
                nap.flatProd(fk),
                gt0,
                gt1,
                gt2,
                gt0.flatProd(f),
                gt1.flatProd(f),
                gt2.flatProd(f),
                gt0.flatProd(fk),
                gt1.flatProd(fk),
                gt2.flatProd(fk));
          }
          break;
        default:
          break;
        }
      }

      // Role co-occurrence
      if (config.roleCoocFeature) {
        ProductIndex gf = NUM_ARG_I;
        switch (actionType) {
        case NEW_K:
        case COMPLETE_K:
        case COMPLETE_S:
          int k1 = config.frPacking.index(fi.f, ri.k);
          int k0 = config.frPacking.index(fi.f);
          int n = config.frPacking.size();
          for (RILL cur = fi.args; cur != null; cur = cur.next) {
            RI ri2 = cur.item;
            if (ri2 == ri)
              continue;
            Span s1 = ri.s;
            if (s1 == Span.nullSpan)    // TODO Build skip-list
              continue;
            int k2 = config.frPacking.index(fi.f, ri2.k);
            int p;
            if (k1 < k2) {
              p = k1 * n + k2;
            } else if (k1 > k2) {
              p = k2 * n + k1;
            } else {
              p = k0 * n + k0;
            }
            feats.add(gf.prod(p, n * n));
          }
          break;
        default:
          break;
        }
      }

      return feats;
    }
  }

}
