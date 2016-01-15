package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.fnparse.data.RolePacking;
import edu.jhu.hlt.fnparse.data.propbank.RoleType;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.rl.full.Info;
import edu.jhu.hlt.fnparse.rl.full.StepScores;
import edu.jhu.hlt.fnparse.rl.params.GlobalFeature;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.tutils.ExperimentProperties;
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
  public static boolean DEBUG_SHOW_BACKWARDS = false;

  public static String[] DEBUG_ALPH = null;
//      new String[ExperimentProperties.getInstance().getInt("hashingTrickDim", 1 << 24)];

  private static final ExperimentProperties C = ExperimentProperties.getInstance();
  public static final boolean ANY_GLOBALS = C.getBoolean("ANY_GLOBALS", true);

  public static final boolean ARG_LOC = C.getBoolean("ARG_LOC", ANY_GLOBALS);
  public static final boolean ROLE_COOC = C.getBoolean("ROLE_COOC", ANY_GLOBALS);
  public static final boolean NUM_ARGS = C.getBoolean("NUM_ARGS", ANY_GLOBALS);

  public static final ProductIndex ARG_LOC_TA =
      ARG_LOC && C.getBoolean("ARG_LOC_TA", false) ? new ProductIndex(0, 16) : null;
  public static final ProductIndex ARG_LOC_TA_F =
      ARG_LOC && C.getBoolean("ARG_LOC_TA_F", false) ? new ProductIndex(1, 16) : null;
  public static final ProductIndex ARG_LOC_TA_FK =
      ARG_LOC && C.getBoolean("ARG_LOC_TA_FK", true) ? new ProductIndex(2, 16) : null;
  public static final ProductIndex ARG_LOC_TA_K =
      ARG_LOC && C.getBoolean("ARG_LOC_TA_K", true) ? new ProductIndex(3, 16) : null;

  public static final ProductIndex ARG_LOC_AA =
      ARG_LOC && C.getBoolean("ARG_LOC_AA", false) ? new ProductIndex(4, 16) : null;
  public static final ProductIndex ARG_LOC_AA_F =
      ARG_LOC && C.getBoolean("ARG_LOC_AA_F", true) ? new ProductIndex(5, 16) : null;
  public static final ProductIndex ARG_LOC_AA_FK =
      ARG_LOC && C.getBoolean("ARG_LOC_AA_FK", false) ? new ProductIndex(6, 16) : null;
  public static final ProductIndex ARG_LOC_AA_K =
      ARG_LOC && C.getBoolean("ARG_LOC_AA_K", false) ? new ProductIndex(7, 16) : null;

  public static final ProductIndex ROLE_COOC_TA =
      ROLE_COOC && C.getBoolean("ROLE_COOC_TA", false) ? new ProductIndex(8, 16) : null;
  public static final ProductIndex ROLE_COOC_TA_F =
      ROLE_COOC && C.getBoolean("ROLE_COOC_TA_F", true) ? new ProductIndex(9, 16) : null;
  public static final ProductIndex ROLE_COOC_TA_FK =
      ROLE_COOC && C.getBoolean("ROLE_COOC_TA_FK", true) ? new ProductIndex(10, 16) : null;
  public static final ProductIndex ROLE_COOC_TA_K =
      ROLE_COOC && C.getBoolean("ROLE_COOC_TA_K", false) ? new ProductIndex(11, 16) : null;

  public static final ProductIndex NUM_ARGS_TA =
      NUM_ARGS && C.getBoolean("NUM_ARGS_TA", false) ? new ProductIndex(12, 16) : null;
  public static final ProductIndex NUM_ARGS_TA_F =
      NUM_ARGS && C.getBoolean("NUM_ARGS_TA_F", true) ? new ProductIndex(13, 16) : null;
  public static final ProductIndex NUM_ARGS_TA_FK =
      NUM_ARGS && C.getBoolean("NUM_ARGS_TA_FK", false) ? new ProductIndex(14, 16) : null;
  public static final ProductIndex NUM_ARGS_TA_K =
      NUM_ARGS && C.getBoolean("NUM_ARGS_TA_K", true) ? new ProductIndex(15, 16) : null;

  public static void logGlobalFeatures(boolean oneLine) {
    String sep = oneLine ? " " : "\n";
    StringBuilder sb = new StringBuilder();
    sb.append("[main]");
    sb.append(sep + "LLSSPatF.ARG_LOC=" + ARG_LOC);
    sb.append(sep + "LLSSPatF.NUM_ARGS=" + NUM_ARGS);
    sb.append(sep + "LLSSPatF.ROLE_COOC=" + ROLE_COOC);
    sb.append(sep + "ARG_LOC_TA=" + ARG_LOC_TA);
    sb.append(sep + "ARG_LOC_TA_F=" + ARG_LOC_TA_F);
    sb.append(sep + "ARG_LOC_TA_FK=" + ARG_LOC_TA_FK);
    sb.append(sep + "ARG_LOC_TA_K=" + ARG_LOC_TA_K);
    sb.append(sep + "ARG_LOC_AA=" + ARG_LOC_AA);
    sb.append(sep + "ARG_LOC_AA_F=" + ARG_LOC_AA_F);
    sb.append(sep + "ARG_LOC_AA_FK=" + ARG_LOC_AA_FK);
    sb.append(sep + "ARG_LOC_AA_K=" + ARG_LOC_AA_K);
    sb.append(sep + "ROLE_COOC_TA=" + ROLE_COOC_TA);
    sb.append(sep + "ROLE_COOC_TA_F=" + ROLE_COOC_TA_F);
    sb.append(sep + "ROLE_COOC_TA_FK=" + ROLE_COOC_TA_FK);
    sb.append(sep + "ROLE_COOC_TA_K=" + ROLE_COOC_TA_K);
    sb.append(sep + "NUM_ARGS_TA=" + NUM_ARGS_TA);
    sb.append(sep + "NUM_ARGS_TA_F=" + NUM_ARGS_TA_F);
    sb.append(sep + "NUM_ARGS_TA_FK=" + NUM_ARGS_TA_FK);
    sb.append(sep + "NUM_ARGS_TA_K=" + NUM_ARGS_TA_K);
    Log.info(sb.toString());
  }

  public static final boolean DEBUG_HURRY_UP = false;

  // Each entry in this is a prefix-sum of all of the global features/weights
  // that went into scoring the actions that lead to this list.
  public final LL<Adjoints> scores;

  // A bit set of realized roles. When C/R roles are used, this is used as an
  // O(1) lookup to determine if a C/R K valued node is licensed (else it is
  // given score(prune)=infinity).
  public final long realizedBaseRoles;

  public LLSSPatF(Node2 item, LLSSPatF next, Info info, ProductIndexWeights weights) {
    super(item, next);
    if (item.prefix.car().type != TFKS.K)
      throw new IllegalArgumentException();

    Adjoints curFeats;
    long kMask = 0;
    if (DEBUG_HURRY_UP) {
      curFeats = Adjoints.Constant.ZERO;
    } else {
      curFeats = newFeatures(item, next, info, weights);
      IntPair kq;
      if (item.firstChildMatchesType(TFKS.S) &&
          (kq = FNParseTransitionScheme.getRole(item.prefix, info)).second == RoleType.BASE.ordinal()) {
        assert FNParseTransitionScheme.getArgSpan(item.children.car().prefix, info) != Span.nullSpan;
        kMask = 1L << kq.first;
      }
    }

    if (next == null) {
      scores = new LL<>(Adjoints.cacheIfNeeded(curFeats), null);
      realizedBaseRoles = kMask;
    } else {
      Adjoints sum = Adjoints.cacheSum(curFeats, next.scores.car());
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
    if (__ssMemo == null) {
      Adjoints addIn = scores.car();
      __ssMemo = super.getScoreSum().plusModel(addIn);
    }
    return __ssMemo;
  }
  private StepScores<?> __ssMemo = null;

  @Override
  public LLSSPatF cdr() {
    return (LLSSPatF) next;
  }

  protected Adjoints newFeatures(Node2 item, LLSSPatF prev, Info info, ProductIndexWeights globals) {
//    List<ProductIndex> feats = new ArrayList<>(16);
    LL<ProductIndex> feats = null;
    if (ARG_LOC)
      feats = argLocSimple(item, prev, info, feats);
    if (ROLE_COOC)
      feats = roleCooc(item, prev, info, feats);
    if (NUM_ARGS)
      feats = numArgs(item, prev, info, feats);
//    if (feats.isEmpty())
    if (feats == null)
      return Adjoints.Constant.ZERO;
//    boolean attemptApplyL2Update = false;   // done in Update instead!
//    ProductIndexAdjoints pia = new ProductIndexAdjoints(globals, feats, attemptApplyL2Update);
//    if (AbstractTransitionScheme.DEBUG && DEBUG_SHOW_BACKWARDS) {
//      pia.nameOfWeights = "LLSSPatF-dyn";
//      pia.showUpdatesWithAlt = DEBUG_ALPH;
//    }
//    return pia;

//    boolean convertToArray = false;
//    return globals.score(feats, convertToArray);
    return globals.score(feats);
  }

//  private static void numArgs(Node2 item, LLSSPatF prev, Info info, List<ProductIndex> addTo) {
  private static LL<ProductIndex> numArgs(Node2 item, LLSSPatF prev, Info info, LL<ProductIndex> addTo) {

    int numArgs = 1 + LL.length(prev);
    if (AbstractTransitionScheme.DEBUG && DEBUG)
      Log.info("numArgs=" + numArgs);

    Frame frame = FNParseTransitionScheme.getFrame(item.prefix, info);
    FrameRolePacking frp = info.getFRPacking();
    RolePacking rp = info.getRPacking();

    assert item.getType() == TFKS.K;
    int k = rp.getRole(frame, item.getValue());
    int K = rp.size();

    int f = frame.getId();
    int F = frp.getNumFrames();

    int fk = frp.index(frame, item.getValue());
    int FK = frp.size();

    ProductIndex na = BasicFeatureTemplates.discretizeWidth2(1, 5, numArgs);
//    if (NUM_ARGS_TA != null)
//      addTo.add(NUM_ARGS_TA.flatProd(na));
//    if (NUM_ARGS_TA_F != null)
//      addTo.add(NUM_ARGS_TA_F.flatProd(na).prod(f, F));
//    if (NUM_ARGS_TA_FK != null)
//      addTo.add(NUM_ARGS_TA_FK.flatProd(na).prod(fk, FK));
//    if (NUM_ARGS_TA_K != null)
//      addTo.add(NUM_ARGS_TA_K.flatProd(na).prod(k, K));
    if (NUM_ARGS_TA != null)
      addTo = new LL<>(NUM_ARGS_TA.flatProd(na), addTo);
    if (NUM_ARGS_TA_F != null)
      addTo = new LL<>(NUM_ARGS_TA_F.flatProd(na).prod(f, F), addTo);
    if (NUM_ARGS_TA_FK != null)
      addTo = new LL<>(NUM_ARGS_TA_FK.flatProd(na).prod(fk, FK), addTo);
    if (NUM_ARGS_TA_K != null)
      addTo = new LL<>(NUM_ARGS_TA_K.flatProd(na).prod(k, K), addTo);

    if (AbstractTransitionScheme.DEBUG && DEBUG_SHOW_BACKWARDS) {
      Log.warn("re-implement me");
//      int pnai = pna.getProdFeatureSafe();
////      assert DEBUG_ALPH[pnai] == null;
//      DEBUG_ALPH[pnai] = "numArgs=" + numArgs;
//      int pnafi = pna.prod(f, N).getProdFeatureSafe();
////      assert DEBUG_ALPH[pnafi] == null;
//      DEBUG_ALPH[pnafi] = "numArgs=" + numArgs + " & frame=" + frame.getName();
//      int pnafki = pna.prod(fk, N).getProdFeatureSafe();
////      assert DEBUG_ALPH[pnafki] == null;
//      DEBUG_ALPH[pnafki] = "numArgs=" + numArgs + " & frame=" + frame.getName() + " & role=" + frame.getRole(k);
    }
    return addTo;
  }

//  private static void roleCooc(Node2 item, LLSSPatF prev, Info info, List<ProductIndex> addTo) {
  private static LL<ProductIndex> roleCooc(Node2 item, LLSSPatF prev, Info info, LL<ProductIndex> addTo) {

    Frame frame = FNParseTransitionScheme.getFrame(item.prefix, info);
    FrameRolePacking frp = info.getFRPacking();
    RolePacking rp = info.getRPacking();

    assert item.getType() == TFKS.K;
    int k1 = rp.getRole(frame, item.getValue());
    int K = rp.size();

    int f = frame.getId();
    int F = frp.getNumFrames();

    int fk = frp.index(frame, item.getValue());
    int FK = frp.size();

    // Other targets?
    // If we want features on other targets, we would have to:
    // 1) design an LLSSPatT which would accumulate these values or,
    // 2) [not feasible w/o significant refactor] pass in a spine:LL<Node2>.
    //    This is difficult because these features are fired from consChild.

    int T = 3;
    int t = Math.min(T-1, LLSSP.length(prev));
    for (LLSSPatF cur = prev; cur != null; cur = cur.cdr()) {

      Node2 otherK = cur.car();
      assert otherK.getType() == TFKS.K;
      int k2 = rp.getRole(frame, otherK.getValue());

      int kk = Math.min(k1, k2) * K + Math.max(k1, k2);
      ProductIndex p0 = new ProductIndex(kk, K*K);
      ProductIndex p1 = p0.prod(0, T+1);
      ProductIndex p2 = p0.prod(t+1, T+1);
//      if (ROLE_COOC_TA != null) {
//        addTo.add(ROLE_COOC_TA.flatProd(p1));
//        addTo.add(ROLE_COOC_TA.flatProd(p2));
//      }
//      if (ROLE_COOC_TA_F != null) {
//        addTo.add(ROLE_COOC_TA_F.flatProd(p1).prod(f, F));
//        addTo.add(ROLE_COOC_TA_F.flatProd(p2).prod(f, F));
//      }
//      if (ROLE_COOC_TA_FK != null) {
//        addTo.add(ROLE_COOC_TA_FK.flatProd(p1).prod(fk, FK));
//        addTo.add(ROLE_COOC_TA_FK.flatProd(p2).prod(fk, FK));
//      }
//      if (ROLE_COOC_TA_K != null) {
//        addTo.add(ROLE_COOC_TA_K.flatProd(p1).prod(k1, K));
//        addTo.add(ROLE_COOC_TA_K.flatProd(p2).prod(k1, K));
//      }
      if (ROLE_COOC_TA != null) {
        addTo = new LL<>(ROLE_COOC_TA.flatProd(p1), addTo);
        addTo = new LL<>(ROLE_COOC_TA.flatProd(p2), addTo);
      }
      if (ROLE_COOC_TA_F != null) {
        addTo = new LL<>(ROLE_COOC_TA_F.flatProd(p1).prod(f, F), addTo);
        addTo = new LL<>(ROLE_COOC_TA_F.flatProd(p2).prod(f, F), addTo);
      }
      if (ROLE_COOC_TA_FK != null) {
        addTo = new LL<>(ROLE_COOC_TA_FK.flatProd(p1).prod(fk, FK), addTo);
        addTo = new LL<>(ROLE_COOC_TA_FK.flatProd(p2).prod(fk, FK), addTo);
      }
      if (ROLE_COOC_TA_K != null) {
        addTo = new LL<>(ROLE_COOC_TA_K.flatProd(p1).prod(k1, K), addTo);
        addTo = new LL<>(ROLE_COOC_TA_K.flatProd(p2).prod(k1, K), addTo);
      }
    }
    return addTo;
  }

  /**
   * Mean to mimic {@link GlobalFeature.ArgLocSimple}
   * @return addTo
   */
//  private List<ProductIndex> argLocSimple(Node2 item, LLSSPatF prev, Info info, List<ProductIndex> addTo) {
  private LL<ProductIndex> argLocSimple(Node2 item, LLSSPatF prev, Info info, LL<ProductIndex> addTo) {
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

      FrameRolePacking frp = info.getFRPacking();
      RolePacking rp = info.getRPacking();

      int k = rp.getRole(frame, item.getValue());
      int K = rp.size();

      int f = frame.getId();
      int F = frp.getNumFrames();

      int fk = frp.index(frame, item.getValue());
      int FK = frp.size();

      // Span of next argument to be added
      Span argCur = FNParseTransitionScheme.getArgSpan(itemChild.prefix, info);
      ProductIndex t2a = BasicFeatureTemplates.spanPosRel2(target, argCur);
//      if (ARG_LOC_TA != null)
//        addTo.add(ARG_LOC_TA.flatProd(t2a));
//      if (ARG_LOC_TA_F != null)
//        addTo.add(ARG_LOC_TA_F.flatProd(t2a).prod(f, F));
//      if (ARG_LOC_TA_FK != null)
//        addTo.add(ARG_LOC_TA_FK.flatProd(t2a).prod(fk, FK));
//      if (ARG_LOC_TA_K != null)
//        addTo.add(ARG_LOC_TA_K.flatProd(t2a).prod(k, K));
      if (ARG_LOC_TA != null)
        addTo = new LL<>(ARG_LOC_TA.flatProd(t2a), addTo);
      if (ARG_LOC_TA_F != null)
        addTo = new LL<>(ARG_LOC_TA_F.flatProd(t2a).prod(f, F), addTo);
      if (ARG_LOC_TA_FK != null)
        addTo = new LL<>(ARG_LOC_TA_FK.flatProd(t2a).prod(fk, FK), addTo);
      if (ARG_LOC_TA_K != null)
        addTo = new LL<>(ARG_LOC_TA_K.flatProd(t2a).prod(k, K), addTo);

      if (AbstractTransitionScheme.DEBUG && DEBUG)
        Log.info("target=" + target.shortString() + " argCur=" + argCur.shortString());

      // Spans of existing arguments
      for (LLSSPatF cur = prev; cur != null; cur = cur.cdr()) {
        Node2 otherS = cur.car();
        if (otherS.firstChildMatchesType(TFKS.S)) {
          Span argPrev = FNParseTransitionScheme.getArgSpan(otherS.children.car().prefix, info);

          ProductIndex a2a = BasicFeatureTemplates.spanPosRel2(argPrev, argCur);
//          if (ARG_LOC_AA != null)
//            addTo.add(ARG_LOC_AA.flatProd(a2a));
//          if (ARG_LOC_AA_F != null)
//            addTo.add(ARG_LOC_AA_F.flatProd(a2a).prod(f, F));
//          if (ARG_LOC_AA_FK != null)
//            addTo.add(ARG_LOC_AA_FK.flatProd(a2a).prod(fk, FK));
//          if (ARG_LOC_AA_K != null)
//            addTo.add(ARG_LOC_AA_K.flatProd(a2a).prod(k, K));
          if (ARG_LOC_AA != null)
            addTo = new LL<>(ARG_LOC_AA.flatProd(a2a), addTo);
          if (ARG_LOC_AA_F != null)
            addTo = new LL<>(ARG_LOC_AA_F.flatProd(a2a).prod(f, F), addTo);
          if (ARG_LOC_AA_FK != null)
            addTo = new LL<>(ARG_LOC_AA_FK.flatProd(a2a).prod(fk, FK), addTo);
          if (ARG_LOC_AA_K != null)
            addTo = new LL<>(ARG_LOC_AA_K.flatProd(a2a).prod(k, K), addTo);

          if (AbstractTransitionScheme.DEBUG && DEBUG)
            Log.info("argPrev=" + argPrev.shortString() + " argCur=" + argCur.shortString());
//        } else {
//          boolean prunedAll = otherS.eggs == null && otherS.pruned != null && otherS.children == null;
          // TODO make a feature for this
        }
      }

    }
    return addTo;
  }
}
