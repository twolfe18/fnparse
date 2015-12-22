package edu.jhu.hlt.fnparse.rl.full2;

import java.io.PrintStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import edu.jhu.hlt.fnparse.rl.full.MaxLoss;
import edu.jhu.hlt.tutils.Log;

public class Node2 implements HasMaxLoss {

  public static boolean DEBUG = false;

  /**
   * If children are {@link PrimesLL}, then this will let you get out a
   * signature
   */
  public static class NodeWithSignature extends Node2 {
    public NodeWithSignature(TFKS prefix, LL<TVN> eggs, LLTVN pruned, LLML<Node2> children) {
      super(prefix, eggs, pruned, children);
      assert children == null || children instanceof PrimesLL;
    }
    public BigInteger getSignature() {
      if (children == null)
        return BigInteger.ZERO;
      if (isLeaf())
        return ((PrimesLL) children).getPrimesProduct();
      BigInteger p = BigInteger.ONE;
      for (LL<Node2> cur = children; cur != null; cur = cur.cdr()) {
        NodeWithSignature cs = (NodeWithSignature) cur.item;
        p = p.multiply(cs.getSignature());
      }
      return p;
    }
  }

  /** Recursively counts number of children Node2s */
  public static int numChildren(Node2 n) {
    int c = 1;
    for (LL<Node2> cur = n.children; cur != null; cur = cur.cdr())
      c += numChildren(cur.car());
    return c;
  }

  // Conceptual note: each of these is a sub-class of either LL<TVN> or LL<Node2>
  public final TFKS prefix;        // path from root, including teis nodes (type,value)
  public final LL<TVN> eggs;
  public final LLTVN pruned;
  public final LLML<Node2> children;

  // I've goofed with numDetermined in that things are more complex than they should be!
  // numDetermined = 1 + sum(map(possible, pruned)) + sum(map(numDetermined, children))
  // fp = gold.contains(this.prefix)?0:1 + sum(map(fp, children))   => children==null implies last term is 0
  // fn = sum(map(fn, pruned))     => fn in eggs:LL<TVN> should store counts
  /*
   * Again with TVN as it currently is:
   *   numPossible = set by user
   *   numDetermined = 1 + sum(map(numPossible, pruned)) + sum(map(numDetermined, children))
   *   fp = this.prefix.gold?0:1 + sum(map(fp, children))
   *   fn = sum(map(tvn -> tvn.gold ? 1 : 0, pruned)) + sum(map(fn, children))
   */
  public final MaxLoss loss;

  public Node2(TFKS prefix, LL<TVN> eggs, LLTVN pruned, LLML<Node2> children) {
    super();
    this.prefix = prefix;
    this.eggs = eggs;
    this.pruned = pruned;
    this.children = children;
//    MaxLoss tl = prefix == null ? MaxLoss.ZERO : prefix.car().getLoss();  // e.g. if this is a T node, says whether (t,???) is fp
//    MaxLoss eL = eggs == null ? MaxLoss.ZERO : eggs.getLoss();
//    MaxLoss pL = pruned == null ? MaxLoss.ZERO : pruned.getLoss();
//    MaxLoss cL = children == null ? MaxLoss.ZERO : children.getLoss();
//    this.loss = new MaxLoss(
//        1 + eL.numPossible + pL.numPossible + cL.numPossible,
//        1 + pL.numPossible + cL.numDetermined,
//        cL.fp + tl.fp,
//        cL.fn + pL.fn);
    
    /*
     * This may be the NPE of doom (when prefix is null).
     * (Do) we need to know goldMatching for every node in the tree?
     * Could we make an exception for root since it is never a child?
     *
     * If its not goldMatching, its going to be possible...
     */
    int thisFP, possible;
    if (prefix == null) {
      if (AbstractTransitionScheme.DEBUG)
        Log.info("prefix IS null");
      // Compute for root
      thisFP = 0;
      possible = 0;
      for (LL<TVN> cur = eggs; cur != null; cur = cur.cdr())
        possible += cur.car().numPossible;
      for (LL<TVN> cur = pruned; cur != null; cur = cur.cdr())
        possible += cur.car().numPossible;
      if (children != null)
        possible += children.getLoss().numPossible;
    } else {
      if (AbstractTransitionScheme.DEBUG)
        Log.info("prefix is NOT null");
      thisFP = prefix.car().goldMatching == 0 ? 1 : 0;
      possible = prefix.car().numPossible;
    }
    int det = LLTVN.sumPossible(pruned) + LL.length(children);
    int fp = thisFP + LLTVN.sumGoldMatching(pruned);
    int fn = LLTVN.numGoldMatchingEqZero(pruned);
    if (AbstractTransitionScheme.DEBUG) {
      Log.info("possible=" + possible + " det=" + det + " fp=" + fp + " fn=" + fn);
      Log.info("LLTVN.sumPossible(pruned)=" + LLTVN.sumPossible(pruned));
      Log.info("LL.length(children)=" + LL.length(children));
      Log.info("LLTVN.sumGoldMatching(pruned)=" + LLTVN.sumGoldMatching(pruned));
      Log.info("children=" + children);
      Log.info("prefix=" + prefix);
      int i = 0;
      for (LL<Node2> cur = children; cur != null; cur = cur.cdr()) {
        TFKS childPrefix = cur.car().prefix;
        Log.info("child[" + (i++) + "] isRefinement=" + TFKS.isRefinement(prefix, childPrefix) + " child=" + cur.car());
        System.out.flush();
      }
      System.out.flush();
    }
    this.loss = new MaxLoss(possible, det, fp, fn);
  }

  /**
   * MaxLoss {
   *  possible = sum(eggs, pruned, children)
   *  determined = sum(pruned, chlidren)
   *  fp = sum(children).fp
   *  fn = sum(pruned).fn
   * }
   */
  @Override
  public MaxLoss getLoss() {
    return loss;
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
        + " loss=" + loss
        + " nEgg=" + LL.length(eggs)
        + " nPrune=" + LL.length(pruned)
        + " nChild=" + LL.length(children)
        + ")";
  }

  public void show(PrintStream ps) { show(ps, ""); }
  public void show(PrintStream ps, String indent) {
    ps.printf("%sNode %s  %s\n", indent, dbgGetTVStr(), loss);
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
      for (LL<Node2> cur = children; cur != null; cur = cur.cdr(), i++)
        cur.car().show(ps, indent);
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
    return prefix.item.getType();
  }
  public int getValue() {
    if (prefix == null)
      return -1;
    return prefix.item.getValue();
  }

  public String dbgGetTVStr() {
    StringBuilder sb = null;
    for (LL<TVN> cur = prefix; cur != null; cur = cur.cdr()) {
      if (sb == null)
        sb = new StringBuilder();
      else
        sb.append(" -> ");
      sb.append(typeName(cur.car().getType()));
      sb.append(':');
      sb.append(String.valueOf(cur.car().getValue()));
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

  public static BitSet getTypes(LL<TVN> items) {
    BitSet bs = new BitSet();
    for (LL<TVN> cur = items; cur != null; cur = cur.next)
      bs.set(cur.item.getType());
    return bs;
  }

}
