package edu.jhu.hlt.fnparse.rl.full2;

import java.io.PrintStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public class Node2 {

  public static boolean DEBUG = false;

  /**
   * If children are {@link PrimesLL}, then this will let you get out a
   * signature
   */
  public static class NodeWithSignature extends Node2 {
    public NodeWithSignature(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children) {
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

  public final LL<TV> prefix;        // path from root, including teis nodes (type,value)
  public final LL<TV> eggs;
  public final LL<TV> pruned;
  public final LL<Node2> children;

  public Node2(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children) {
    super();
    this.prefix = prefix;
    this.eggs = eggs;
    this.pruned = pruned;
    this.children = children;
  }

  public boolean isLeaf() {
    boolean leaf = eggs == null && children == null;
    if (leaf) assert pruned == null;
    return leaf;
  }

  @Override
  public String toString() {
//    StringBuilder sb = new StringBuilder("(Node\n");
//    sb.append(" prefix=" + prefix + "\n");
//    sb.append(" eggs=" + eggs + "\n");
//    sb.append(" pruned=" + pruned + "\n");
//    sb.append(" children=" + children + "\n");
//    sb.append(')');
//    return sb.toString();
    String p = prefix == null ? "null" : ((TFKS) prefix).str();
    return "(Node2 " + p
        + " nEgg=" + LL.length(eggs)
        + " nPrune=" + LL.length(pruned)
        + " nChild=" + LL.length(children)
        + ")";
  }

  public void show(PrintStream ps) { show(ps, ""); }
  public void show(PrintStream ps, String indent) {
//    ps.printf("%sNode %s  %s\n", indent, getTypeStr(), dbgGetTVStr());
    ps.printf("%sNode %s\n", indent, dbgGetTVStr());
    indent = "  " + indent;
    int i;
    if (eggs == null) {
      ps.printf("%seggs == NIL\n", indent);
    } else {
      i = 0;
      for (LL<TV> cur = eggs; cur != null; cur = cur.cdr(), i++)
        ps.printf("%segg[%d] %s\n", indent, i, cur.car());
    }

    if (pruned == null) {
      ps.printf("%sprune == NIL\n", indent);
    } else {
      i = 0;
      for (LL<TV> cur = pruned; cur != null; cur = cur.cdr(), i++)
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
    for (LL<TV> cur = prefix; cur != null; cur = cur.cdr()) {
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

  public static BitSet getTypes(LL<TV> items) {
    BitSet bs = new BitSet();
    for (LL<TV> cur = items; cur != null; cur = cur.next)
      bs.set(cur.item.getType());
    return bs;
  }
}
