package edu.jhu.hlt.fnparse.rl.full2;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.data.propbank.RoleType;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.rl.full.Info;
import edu.jhu.hlt.fnparse.rl.full.StepScores;
import edu.jhu.hlt.fnparse.rl.full.weights.ProductIndexAdjoints;
import edu.jhu.hlt.fnparse.rl.full.weights.WeightsInfo;
import edu.jhu.hlt.fnparse.rl.params.GlobalFeature;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;

/**
 * This is the list of children (K nodes) at an F node. It maintains indices
 * like the number of realized spans. This is a sub-class of LL which stores
 * the Adjoints representing global features, unlike the static features which
 * are stored in {@link Node2#prefix} ({@link TVNS}). The way that these
 * features make their way into the total score of a tree is via the {@link Node2}
 * constructor calling {@link LLSSP#getScoreSum()} which normally stores the
 * sum of all the prefix scores for children/subtrees, but here has been over-
 * ridden to include the global features.
 *
 * The implementation I'm going to use right now assumes that you generate
 * features on every cons action and re-use the cached features from next:LL.
 * TODO Another optimization is to globally cache the feature combination work
 * that is done for every cons action (this could be memoized across an entire
 * search knowning that you will perform cons(a, b::c::d) multiple times and can
 * store f(a, b::c::d) -> LL in some hash table somewhere.
 *
 * @author travis
 */
public class LLSSPatF extends LLSSP {

  public static boolean DEBUG = false;

  public static boolean ARG_LOC = true;
  public static boolean ROLE_COOC = true;
  public static boolean NUM_ARGS = true;

  private static final ProductIndex argLocPI = new ProductIndex(0, 3);
  private static final ProductIndex roleCoocPI = new ProductIndex(1, 3);
  private static final ProductIndex numArgsPI = new ProductIndex(2, 3);

  // Each entry in this is a prefix-sum of all of the global features/weights
  // that went into scoring the actions that lead to this list.
  public final LL<Adjoints> scores;

  // A bit set of realized roles. When C/R roles are used, this is used as an
  // O(1) lookup to determine if a C/R K valued node is licensed (else it is
  // given score(prune)=infinity).
  public final long realizedBaseRoles;

  public LLSSPatF(Node2 item, LLSSPatF next, Info info, WeightsInfo weights) {
    super(item, next);
    if (item.prefix.car().type != TFKS.K)
      throw new IllegalArgumentException();

    Adjoints curFeats = newFeatures(item, next, info, weights);

    IntPair kq;
    long kMask = 0;
    if (item.firstChildMatchesType(TFKS.S) &&
        (kq = FNParseTransitionScheme.getRole(item.prefix, info)).second == RoleType.BASE.ordinal()) {
      assert FNParseTransitionScheme.getArgSpan(item.children.car().prefix, info) != Span.nullSpan;
      kMask = 1L << kq.first;
    }

    if (next == null) {
      scores = new LL<>(Adjoints.cacheIfNeeded(curFeats), null);
      realizedBaseRoles = kMask;
    } else {
      Adjoints sum = Adjoints.cacheIfNeeded(new Adjoints.Sum(curFeats, next.scores.car()));
      scores = new LL<>(sum, next.scores);
      realizedBaseRoles = kMask | next.realizedBaseRoles;
    }

    /*
     * LLSSP:    (sum, adj) -> (sum', adj') -> ...
     *           getScoreSum:StepScores
     * LLSSPatF: need to override getScoreSum in order for Node2.init/score to work.
     * options:
     * 1) do monkey-business associated with inserting Adjoints into item/LLSSP
     * 2) only override getScoreSum and maintain separate data structures for global features.
     */
  }

  public boolean realizedRole(int k) {
    return (realizedBaseRoles & (1L << k)) != 0;
  }

  @Override
  public StepScores<?> getScoreSum() {
    // Putting this here instead of the constructor so that I'm sure super.getScoreSum() will work properly
    if (__ssMemo == null)
      __ssMemo = super.getScoreSum().plusModel(scores.car());
    return __ssMemo;
  }
  private StepScores<?> __ssMemo = null;

  @Override
  public LLSSPatF cdr() {
    return (LLSSPatF) next;
  }

  protected Adjoints newFeatures(Node2 item, LLSSPatF prev, Info info, WeightsInfo globals) {
    List<ProductIndex> feats = new ArrayList<>();
    if (ARG_LOC)
      argLocSimple(item, prev, info, feats);
    if (ROLE_COOC)
      roleCooc(item, prev, info, feats);
    if (NUM_ARGS)
      numArgs(item, prev, info, feats);
    if (feats.isEmpty())
      return Adjoints.Constant.ZERO;
    boolean attemptApplyL2Update = false;   // done in Update instead!
    return new ProductIndexAdjoints(globals, feats, attemptApplyL2Update);
  }

  private static void numArgs(Node2 item, LLSSPatF prev, Info info, List<ProductIndex> addTo) {

    int numArgs = 1 + LL.length(prev);
    if (AbstractTransitionScheme.DEBUG && DEBUG)
      Log.info("numArgs=" + numArgs);

    Frame frame = FNParseTransitionScheme.getFrame(item.prefix, info);
    assert item.getType() == TFKS.K;
    int k = item.getValue();
    FrameRolePacking frp = info.getFRPacking();
    int f = frp.index(frame);
    int fk = frp.index(frame, k);
    int N = frp.size();

    ProductIndex na = BasicFeatureTemplates.discretizeWidth2(1, 5, numArgs);
    ProductIndex pna = numArgsPI.flatProd(na);
    addTo.add(pna);
    addTo.add(pna.prod(f, N));
    addTo.add(pna.prod(fk, N));
  }

  private static void roleCooc(Node2 item, LLSSPatF prev, Info info, List<ProductIndex> addTo) {

    Frame frame = FNParseTransitionScheme.getFrame(item.prefix, info);
    assert item.getType() == TFKS.K;
    int k1 = item.getValue();
    FrameRolePacking frp = info.getFRPacking();
    int f = frp.index(frame);
//    int fk1 = frp.index(frame, k1);
    int N = frp.size();
    int K = frame.numRoles();

    ProductIndex p0 = roleCoocPI.prod(f, N);
    addTo.add(p0);

    // TODO Other targets?
    int T = 3;
    int t = Math.min(T-1, LLSSP.length(prev));
    for (LLSSPatF cur = prev; cur != null; cur = cur.cdr()) {

      Node2 otherK = cur.car();
      assert otherK.getType() == TFKS.K;
      int k2 = otherK.getValue();

      int kk = Math.min(k1, k2) * K + Math.max(k1, k2);
      ProductIndex p1 = p0.prod(kk, K * K);
      addTo.add(p1.prod(0, T+1));
      addTo.add(p1.prod(t+1, T+1));
    }
  }

  /**
   * Mean to mimic {@link GlobalFeature.ArgLocSimple}
   */
  private static void argLocSimple(Node2 item, LLSSPatF prev, Info info, List<ProductIndex> addTo) {
    // We may know whether there is a s:S/Span chosen already.
    // If not, this feature doesn't fire.
    assert item.getType() == TFKS.K;
    if (item.firstChildMatchesType(TFKS.S)) {

      // This should be true of any reasonable decode, but for MV it may not be true
//      assert item.children.length == 1; // Modify features to naturally handle this

      Node2 itemChild = item.children.car();

      // These we know for sure
      Span target = FNParseTransitionScheme.getTarget(item.prefix, info);
      Frame frame = FNParseTransitionScheme.getFrame(item.prefix, info);
      int k = item.getValue();

      // Span of next argument to be added
      Span argCur = FNParseTransitionScheme.getArgSpan(itemChild.prefix, info);
      ProductIndex t2a = BasicFeatureTemplates.spanPosRel2(target, argCur);
      ProductIndex pt2a = argLocPI.prod(true).flatProd(t2a);
      FrameRolePacking frp = info.getFRPacking();
      int f = frp.index(frame);
      int fk = frp.index(frame, k);
      int N = frp.size();
      ProductIndex pt2a_f = pt2a.prod(f, N);
      ProductIndex pt2a_fk = pt2a.prod(fk, N);
      addTo.add(pt2a);
      addTo.add(pt2a_f);
      addTo.add(pt2a_fk);

      if (AbstractTransitionScheme.DEBUG && DEBUG)
        Log.info("target=" + target.shortString() + " argCur=" + argCur.shortString());

      // Spans of existing arguments
      for (LLSSPatF cur = prev; cur != null; cur = cur.cdr()) {
        Node2 otherS = cur.car();
        if (otherS.firstChildMatchesType(TFKS.S)) {
          Span argPrev = FNParseTransitionScheme.getArgSpan(otherS.children.car().prefix, info);

          ProductIndex a2a = BasicFeatureTemplates.spanPosRel2(argPrev, argCur);
          ProductIndex pa2a = argLocPI.prod(false).flatProd(a2a);
          addTo.add(pa2a);
          addTo.add(pa2a.prod(f, N));
          addTo.add(pa2a.prod(fk, N));

          if (AbstractTransitionScheme.DEBUG && DEBUG)
            Log.info("argPrev=" + argPrev.shortString() + " argCur=" + argCur.shortString());
//        } else {
//          boolean prunedAll = otherS.eggs == null && otherS.pruned != null && otherS.children == null;
          // TODO make a feature for this
        }
      }
    }
  }
}
