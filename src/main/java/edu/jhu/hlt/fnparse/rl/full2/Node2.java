package edu.jhu.hlt.fnparse.rl.full2;

import java.io.PrintStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import edu.jhu.hlt.fnparse.rl.full.MaxLoss;
import edu.jhu.hlt.fnparse.rl.full.SearchCoefficients;
import edu.jhu.hlt.fnparse.rl.full.StepScores;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;

/**
 * The basic unit of prediction. Prefix describes a sub-set of the label indices
 * that "match" this node (the first element in prefix gives the "type" and
 * "value" of this node and the root (and only the root) has a null prefix.
 * Eggs are a list of next (type,values) to be tried out. Pruned is the list of
 * eggs which were not deemed worthy and children is the set that are.
 *
 * Note that there are some hidden values stored in the LL sub-types (e.g.
 * {@link TFKS}, {@link LLTVN}, and {@link LLSSP}) such as the cardinality of
 * each node in label indices, the number of gold "yes"s that are dominated by
 * nodes, and the model score of the actions which lead to this structure.
 *
 * @author travis
 */
public class Node2 implements HasStepScores, HasSig {

  // Regular loss is based on the number of false negatives in an entire sub-tree
  // (false positives do not change: they are directly related to the structure
  // of the (non-pruned part of the) tree). With myopic loss, we say that a node
  // either fn \in {0, 1} (for whether there existed a gold item in the sub-tree
  // which was pruned or not). This makes margin constraints a little easier to
  // satisfy and a little more intuitive (e.g. pruning an egg of root may cause
  // 150 FNs. Do we really want score(hatch) - score(prune) >= 150? This pretty
  // much precludes regularization or requires special scaling). The downside is
  // that the model is not trained to recognize big errors from small ones.
  public static boolean MYOPIC_LOSS = false;

  public static boolean DEBUG = false;
  public static boolean DEBUG_INIT = false;

  // Conceptual note: each of these is a sub-class of either LL<TVN> or LL<Node2>
  public final TFKS prefix;   // path from root, including this nodes (type,value)
  public final LLTVN eggs;
  public final LLTVNS pruned;
  public final LLSSP children;

  /* TODO How to index nodes so that you can compute nextStates without touching
   * any nodes that you don't have to?
   * You want to ignore all nodes where eggs==null. Previously I had called this
   * "frozen" (different from pruned). I think rather than have a separate list
   * in Node2 (where nodes go from children -> frozen when they run out of eggs),
   * we should have a pointer into children designating the index after which
   * all Nodes have eggs==null. The solution with a separate frozen list does
   * not work because you would have to remove from children, which cannot be
   * done persistently and efficiently.
   * Note: "frozen" doesn't JUST mean eggs==null, it means eggs==null AND any
   * children (if there are any) are also frozen. If a node is frozen, it cannot
   * generate any new actions, so it is useless to visit in nextStates.
   * When the last hatch/squash occurs which leads to eggs==null for some node,
   * it's parent MAY (there could be other non-frozen siblings) be replaced with
   * a copy where the frozen pointer has been bumped back one position. This may
   * form a cascade of freezes.
   */

  /* How to do the part where we rank eggs by their static score of becoming a child?
   * 1) Sub-class TVN with a special StaticlyScoredEgg. Special checking would need to be done in hatch...
   *    Actually this could be done in scoreHatch wich does (Node2 (eggs TVN ...) ...) -> TVNS
   */

  // Score of the hatch action that lead to this node and the score of any
  // actions taken at/below this node.
  public final StepScores<?> score;

  public Node2(TFKS prefix, LLTVN eggs, LLTVNS pruned, LLSSP children) {
    super();
    this.prefix = prefix;
    this.eggs = eggs;
    this.pruned = pruned;
    this.children = children;

    int possible = 1    // this node
        + LLTVN.sumPossible(eggs)
        + LLTVN.sumPossible(pruned)
        + LLSSP.sumPossible(children);
    // TODO Just use TFKS.numPossible?
    if (prefix != null) {
//      for (TFKS cur = prefix; cur != null; cur = cur.cdr()) {
//        System.err.println(cur.car());
//      }
//      System.err.println("eggs.poss=" + LLTVN.sumPossible(eggs));
//      System.err.println("pruned.poss=" + LLTVN.sumPossible(pruned));
//      System.err.println("children.poss=" + LLSSP.sumPossible(children));
      assert possible == prefix.car().numPossible
        : "possible=" + possible
        + " prefix.car.possible=" + prefix.car().numPossible;
//        + " prefix=" + prefix;
    }

    int det = 1 + LLTVN.sumPossible(pruned) + LLSSP.getSumLoss(children).numDetermined;

    assert possible > 0;
    MaxLoss childrenLoss = LLSSP.getSumLoss(children);

    int thisFP = prefix != null && prefix.car().goldMatching == 0 ? 1 : 0;
    int fp = thisFP + childrenLoss.fp;

    int thisFN = MYOPIC_LOSS
        ? LLTVN.numGoldMatchingGtZero(pruned)
        : LLTVN.sumGoldMatching(pruned);
    int fn = thisFN + childrenLoss.fn;

    if (DEBUG_INIT && AbstractTransitionScheme.DEBUG) {
      Log.info("possible=" + possible + " det=" + det + " fp=" + fp + " fn=" + fn);
      Log.info("LLTVN.sumPossible(pruned)=" + LLTVN.sumPossible(pruned));
      Log.info("LL.length(children)=" + LL.length(children));
      Log.info("LLTVN.sumGoldMatching(pruned)=" + LLTVN.sumGoldMatching(pruned));
      Log.info("children=" + children);
      Log.info("prefix=" + prefix);
      int i = 0;
      for (LLSSP cur = children; cur != null; cur = cur.cdr()) {
        Node2 n = (Node2) cur.car();
        TFKS childPrefix = n.prefix;
        Log.info("child[" + (i++) + "] isRefinement=" + TFKS.isRefinement(prefix, childPrefix) + " child=" + cur.car());
        System.out.flush();
      }
      System.out.flush();
    }

    MaxLoss loss = new MaxLoss(possible, det, fp, fn);
    Adjoints prefixS, prunedS, childrenS;
    double prefixR, prunedR, childrenR;
    if (prefix == null) {
      prefixS = Adjoints.Constant.ZERO;
      prefixR = 0;
    } else {
      // This is the fly in the ointment why everything can't be sums of MaxLoss.
      // We need a TVNS which doesn't have loss yet for prefixes.
      TVNS x = prefix.car();
      prefixS = x.getModel();
      prefixR = x.getRand();
    }
    if (pruned == null) {
      prunedS = Adjoints.Constant.ZERO;
      prunedR = 0;
    } else {
      prunedS = pruned.getModelSum(); // Should not include rand!
      prunedR = pruned.getRandSum();
    }
    if (children == null) {
      childrenS = Adjoints.Constant.ZERO;
      childrenR = 0;
    } else {
      childrenS = children.getScoreSum().getModel();
      childrenR = children.getScoreSum().getRand();
    }
    Adjoints score = Adjoints.cacheIfNeeded(new Adjoints.Sum(prefixS, prunedS, childrenS));
    double rand = prefixR + prunedR + childrenR;
    this.score = new StepScores<>(null, score, loss, rand);
  }

  // TODO BigInteger primesProd -- children already has this
  @Override
  public BigInteger getSig() {
    // Need some way to complete this, could do this by:
    // 1) knowing the length of eggs and pruned (if the egg order is static this is enough)
    // 2) give eggs two primes, one to use if they get squashed and another if they are hatched
    BigInteger a = LLTVN.getPrimesProd(eggs);
    BigInteger b = LLTVN.getPrimesProd(pruned);
    BigInteger c = LLSSP.getPrimeProd(children);
    BigInteger d = TFKS.getPrimesProd(prefix);
    return a.multiply(b).multiply(c).multiply(d);
  }

  @Override
  public int hashCode() {
    return getSig().intValue();
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof Node2)
      return getSig().equals(((Node2) other).getSig());
    return false;
  }

  /**
   * If there are no children, this returns false.
   */
  public boolean firstChildMatchesType(int type) {
    if (children == null)
      return false;
    return children.car().getType() == type;
  }

  @Override
  public StepScores<?> getStepScores() {
    return score;
  }

  public Adjoints getModelScore() {
    return score.getModel();
  }

  public MaxLoss getLoss() {
    return score.getLoss();
  }

  public double getRand() {
    return score.getRand();
  }

  public boolean isLeaf() {
    boolean leaf = eggs == null && children == null;
    if (leaf) assert pruned == null;
    return leaf;
  }

  @Override
  public String toString() {
    String p = prefix == null ? "null" : ((TFKS) prefix).str();
    return "(Node2 " + p
        + " nEgg=" + LL.length(eggs)
        + " nPrune=" + LL.length(pruned)
        + " nChild=" + LL.length(children)
        + " loss=" + getLoss()
        + ")";
  }

  public void show(PrintStream ps) { show(ps, "", null); }
  public void show(PrintStream ps, SearchCoefficients scoreCoefs) { show(ps, "", scoreCoefs); }
  public void show(PrintStream ps, String indent, SearchCoefficients scoreCoefs) {
    ps.printf("%sNode %s\n", indent, dbgGetTVStr());
    indent = "  " + indent;
    ps.printf("%sprefix.car.model=%s\n", indent, prefix == null ? "null" : prefix.car().getModel());
    ps.printf("%sloss=%s\n", indent, getLoss());
    if (scoreCoefs != null)
      ps.printf("%sscore=%s\tcoefs=%s\n", indent, scoreCoefs.forwards(getStepScores()), scoreCoefs);
    int i;
    if (eggs == null) {
      ps.printf("%seggs == NIL\n", indent);
    } else {
      i = 0;
      for (LL<TVN> cur = eggs; cur != null; cur = cur.cdr(), i++)
        ps.printf("%segg[%d] %s\n", indent, i, cur.car());
    }

    if (pruned == null) {
      ps.printf("%sprune == NIL\n", indent);
    } else {
      i = 0;
      for (LL<TVN> cur = pruned; cur != null; cur = cur.cdr(), i++)
        ps.printf("%sprune[%d] %s\n", indent, i, cur.car());
    }

    if (children == null) {
      ps.printf("%schildren == NIL\n", indent);
    } else {
      i = 0;
      for (LLSSP cur = children; cur != null; cur = cur.cdr(), i++) {
        ((Node2) cur.car()).show(ps, indent, scoreCoefs);
      }
    }
  }

  // TODO Remove after debugging (fn-specific)
  public static String typeName(int type) {
    switch (type) {
    case -1: return "ROOT";
    case 0: return "T";
    case 1: return "F";
    case 2: return "K";
    case 3: return "S";
    default:
      throw new RuntimeException();
    }
  }
  public String getTypeStr() {
    return typeName(getType());
  }
  public int getType() {
    if (prefix == null)
      return -1;
    return prefix.car().type;
  }
  public int getValue() {
    if (prefix == null)
      return -1;
    return prefix.car().value;
  }

  public String dbgGetTVStr() {
    StringBuilder sb = null;
    for (TFKS cur = prefix; cur != null; cur = cur.cdr()) {
      if (sb == null)
        sb = new StringBuilder();
      else
        sb.append(" -> ");
      sb.append(typeName(cur.car().type));
      sb.append(':');
      sb.append(String.valueOf(cur.car().value));
    }
    return sb == null ? "ROOT" : sb.toString();
  }

  /** Returns a list of error messages (empty implies everything is good) */
  public List<String> dbgSanityCheck() {
    List<String> errs = new ArrayList<>();
    BitSet pt = getTypes(prefix);

    BitSet et = getTypes(eggs);
    if (et.intersects(pt))
      errs.add("egg types should not overlap with prefix types");

    BitSet prt = getTypes(pruned);
    if (prt.intersects(pt))
      errs.add("pruned types should not overlap with prefix types");

    return errs;
  }

  public void dbgSantityCheckA() {
    List<String> errs = dbgSanityCheck();
    assert errs.isEmpty() : errs;
  }

  public void dbgSantityCheckE() {
    List<String> errs = dbgSanityCheck();
    if (!errs.isEmpty())
      throw new RuntimeException(errs.toString());
  }

  public static BitSet getTypes(LL<?> items) {
    BitSet bs = new BitSet();
    for (LL<?> cur = items; cur != null; cur = cur.next) {
      TVN c = (TVN) cur.car();
      bs.set(c.type);
    }
    return bs;
  }

  /** Recursively counts number of children Node2s */
  public static int numChildren(Node2 n) {
    // Root does not count in the hamming possible worlds setup since it doesn't
    // specify any information, appears in every possible tree. T nodes are the
    // first to make some statement about how the label needs to be.
    boolean isRoot = n.prefix == null;
    int c = isRoot ? 0 : 1;
    for (LL<Node2> cur = n.children; cur != null; cur = cur.cdr())
      c += numChildren(cur.car());
    return c;
  }
}
