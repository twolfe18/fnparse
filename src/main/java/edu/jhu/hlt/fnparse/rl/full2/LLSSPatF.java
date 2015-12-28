package edu.jhu.hlt.fnparse.rl.full2;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.rl.full.Info;
import edu.jhu.hlt.fnparse.rl.full.StepScores;
import edu.jhu.hlt.fnparse.rl.full.weights.ProductIndexAdjoints;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.fnparse.rl.params.GlobalFeature;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.vector.IntDoubleDenseVector;

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

  public static boolean ARG_LOC = true;
  public static boolean ROLE_COOC = true;
  public static boolean NUM_ARGS = true;

  private static final ProductIndex argLocPI = new ProductIndex(0, 3);
  private static final ProductIndex roleCoocPI = new ProductIndex(1, 3);
  private static final ProductIndex numArgsPI = new ProductIndex(2, 3);

  // Each entry in this is a prefix-sum of all of the global features/weights
  // that went into scoring the actions that lead to this list.
  public final LL<Adjoints> scores;

  public double learningRate = 0.05;
  public double l2Reg = 1e-7;
  public int dimension = 1 << 18;
  public int updateInterval = 32;
  public LazyL2UpdateVector weights =
      new LazyL2UpdateVector(new IntDoubleDenseVector(dimension), updateInterval);

  public LLSSPatF(Node2 item, LLSSPatF next, Info info) {
    super(item, next);
    if (item.prefix.car().type != TFKS.K)
      throw new IllegalArgumentException();
    Adjoints curFeats = newFeatures(item, next, info);
    if (next == null)
      scores = new LL<>(curFeats, null);
    else
      scores = new LL<>(curFeats, next.scores);
    /*
     * LLSSP:    (sum, adj) -> (sum', adj') -> ...
     *           getScoreSum:StepScores
     * LLSSPatF: need to override getScoreSum in order for Node2.init/score to work.
     * options:
     * 1) do monkey-business associated with inserting Adjoints into item/LLSSP
     * 2) only override getScoreSum and maintain separate data structures for global features.
     */
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

  protected Adjoints newFeatures(Node2 item, LLSSPatF prev, Info info) {
    List<ProductIndex> feats = new ArrayList<>();
    if (ARG_LOC)
      argLocSimple(item, prev, info, feats);
//    if (ROLE_COOC)
//      Log.warn("need to implement other global features");
    if (NUM_ARGS)
      numArgs(item, prev, info, feats);
    return new ProductIndexAdjoints(learningRate, l2Reg, dimension, feats, weights);
  }

  private void numArgs(Node2 item, LLSSPatF prev, Info info, List<ProductIndex> addTo) {
    int numArgs = 1 + LL.length(prev);
    ProductIndex na = BasicFeatureTemplates.discretizeWidth2(1, 5, numArgs);
    addTo.add(numArgsPI.flatProd(na));
    // TODO frame conjunctions/refinements!
  }

  /**
   * Mean to mimic {@link GlobalFeature.ArgLocSimple}
   */
  private void argLocSimple(Node2 item, LLSSPatF prev, Info info, List<ProductIndex> addTo) {
    // We may know whether there is a s:S/Span chosen already.
    // If not, this feature doesn't fire.
    assert item.getType() == TFKS.K;
    if (item.firstChildMatchesType(TFKS.S)) {

      // This should be true of any reasonable decode, but for MV it may not be true
//      assert item.children.length == 1; // Modify features to naturally handle this

      Node2 itemChild = item.children.car();

      // These we know for sure
      Span target = FNParseTransitionScheme.getTarget(item.prefix, info);
//      Frame frame = FNParseTransitionScheme.getFrame(item.prefix, info);
//      int k = item.getValue();

      // Span of next argument to be added
      Span argCur = FNParseTransitionScheme.getArgSpan(itemChild.prefix, info);
      addTo.add(argLocPI.prod(true).flatProd(BasicFeatureTemplates.spanPosRel2(target, argCur)));

      // Spans of existing arguments
      for (LLSSPatF cur = prev; cur != null; cur = cur.cdr()) {
        Node2 otherS = cur.car();
        if (otherS.firstChildMatchesType(TFKS.S)) {
          Span argPrev = FNParseTransitionScheme.getArgSpan(otherS.children.car().prefix, info);
          addTo.add(argLocPI.prod(false).flatProd(BasicFeatureTemplates.spanPosRel2(argPrev, argCur)));
        } else {
          boolean prunedAll = otherS.eggs == null && otherS.pruned != null && otherS.children == null;
          // TODO make a feature for this
        }
      }

      // TODO Conjoin with frame and frame-role!
//      Log.warn("implement conjunctions!");
    }
  }
}
