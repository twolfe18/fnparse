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

public class Node2 implements HasStepScores, HasSig {

  public static boolean DEBUG = false;
  public static boolean DEBUG_INIT = false;

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

  // Conceptual note: each of these is a sub-class of either LL<TVN> or LL<Node2>
  public final TFKS prefix;        // path from root, including teis nodes (type,value)
  public final LLTVN eggs;
  public final LLTVNS pruned;
  public final LLSSP children;

  // TODO BigInteger primesProd -- children already has this
  @Override
  public BigInteger getSig() {
    // Need some way to complete this, could do this by:
    // 1) knowing the length of eggs and pruned (if the egg order is static this is enough)
    // 2) give eggs two primes, one to use if they get squashed and another if they are hatched
    if (children == null)
      return BigInteger.ONE;
    return children.getPrimeProd();
  }

  /*
   * Sum of the scores (along rand:double, model:Adjoints, and loss:MaxLoss)
   * which lead to the state of this node and its sub-tree.
   * I've worked this out for MaxLoss. We can assume eggs/pruned/children are
   * conditionally (on prefix) disjoint, and MaxLoss can be summed over disjoint
   * sets.
   *
   * First, model score. If we make the modeling assumption that:
   *   s(squash, item | prefix, state?) = -s(hatch, item | prefix, state?)
   * Then things get easier. We need one value 
   * ...this is parameterization, I really should focus on the semantics...
   * Every egg -> (pruned|children) is an action (squash|hatch) respectively.
   * Every node has direct actions and chilren (nodes)
   * Model score is the sum of all the actions for a given node + sum(actions of its children)
   *
   * Knowing how model score works make rand easy: it is just a perturbation of
   * model score and should behave the same way.
   */
  public final StepScores<SearchCoefficients> score;

  // Prunes need to have StepScores so that we can use LLML so that computing MaxLoss for a node is easy
  // HasStepScores => Needs Info (since Info carries coefficients)
  // I think Children need this too, so if you're going to have these infos floating around, you might as well put them in Node2 for easy access
  // ABOVE is bad argument (no longer using LLML, but LLTVNS for pruned)
  // BUT we still want Node2 to have StepScores so that replaceNode/zipUp can do the appropriate work in deriving a score for the entire tree
  // Earlier I had some notes about how having each node have a score is likely necessary for pruning/branch+bound.
  // FINALLY: Even though we do need StepScores, that doesn't mean we need to store the search coefs here.
//  public final SearchCoefficients coefs;
  public SearchCoefficients getCoefs() {
    return score.getInfo();
  }


  public Node2(SearchCoefficients coefs, TFKS prefix, LLTVN eggs, LLTVNS pruned, LLSSP children) {
    super();
    this.prefix = prefix;
    this.eggs = eggs;
    this.pruned = pruned;
    this.children = children;
    /*
     * MaxLoss {
     *  possible = sum(eggs, pruned, children)
     *  determined = sum(pruned, chlidren)
     *  fp = sum(children).fp
     *  fn = sum(pruned).fn
     * }
     */
//    int thisFP, possible;
//    if (prefix == null) {
//      if (DEBUG_INIT && AbstractTransitionScheme.DEBUG)
//        Log.info("prefix IS null");
//      // Compute for root
//      thisFP = 0;
//      possible = 0;
//      for (LL<TVN> cur = eggs; cur != null; cur = cur.cdr())
//        possible += cur.car().numPossible;
//      for (LL<TVN> cur = pruned; cur != null; cur = cur.cdr())
//        possible += cur.car().numPossible;
//      if (children != null)
//        possible += children.getLoss().numPossible;
//    } else {
//      if (DEBUG_INIT && AbstractTransitionScheme.DEBUG)
//        Log.info("prefix is NOT null");
//      thisFP = prefix.car().goldMatching == 0 ? 1 : 0;
//      possible = prefix.car().numPossible;
//    }
    int possible = 1    // this node
        + LLTVN.sumPossible(eggs)
        + LLTVN.sumPossible(pruned)
        + LLSSP.sumPossible(children);
    // TODO Just use TFKS.numPossible?
    if (prefix != null)
      assert possible == prefix.car().numPossible
        : "possible=" + possible + " prefix.car.possible=" + prefix.car().numPossible;

//    int det = LLTVN.sumPossible(pruned) + LL.length(children);
    int det = 1 + LLTVN.sumPossible(pruned) + LLSSP.getSumLoss(children).numDetermined;

    // 1) only in children and thisFP
    // 2) for children you many know numPossible and goldMatching, but you can't say for sure how many FP there are (positives are only laid down at each node)
//    int fp = thisFP + LLTVN.sumGoldMatching(children);
    assert possible > 0;
    int thisFP = prefix != null && prefix.car().goldMatching == 0 ? 1 : 0;
    int fp = thisFP + LLSSP.getSumLoss(children).fp;

    int fn = LLTVN.sumGoldMatching(pruned);
//    int fn = pruned.length() - LLTVN.numGoldMatchingEqZero(pruned);

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
    Adjoints z = Adjoints.Constant.ZERO;
    Adjoints a, b, c;
    double ra, rb, rc;
    if (prefix == null) {
      a = z;
      ra = 0;
    } else {
      // This is the fly in the ointment why everything can't be sums of MaxLoss.
      // We need a TVNS which doesn't have loss yet for prefixes.
      TVNS x = prefix.car();
      a = x.getModel();
      ra = x.getRand();
    }
    if (pruned == null) {
      b = z;
      rb = 0;
    } else {
      b = pruned.getModelSum(); // Should not include rand!
      rb = pruned.getRandSum();
    }
    if (children == null) {
      c = z;
      rc = 0;
    } else {
      c = children.getScoreSum().getModel();
      rc = children.getScoreSum().getRand();
    }
    Adjoints score = new Adjoints.Sum(a, b, c);
    double rand = ra + rb + rc;
    this.score = new StepScores<SearchCoefficients>(coefs, score, loss, rand);
  }

  @Override
  public StepScores<?> getStepScores() {
    return score;
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
        + ")";
  }

  public void show(PrintStream ps) { show(ps, ""); }
  public void show(PrintStream ps, String indent) {
//    ps.printf("%sNode %s  %s\n", indent, dbgGetTVStr(), loss);
    ps.printf("%sNode %s\n", indent, dbgGetTVStr());
    indent = "  " + indent;
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
        ((Node2) cur.car()).show(ps, indent);
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

}
