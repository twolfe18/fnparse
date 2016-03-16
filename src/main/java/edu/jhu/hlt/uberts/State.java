package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.hash.Hash;

/**
 * A hyper-graph representing joint NLP predictions.
 *
 * @see HypEdge, HypNode
 *
 * @author travis
 */
public class State {

  public static boolean DEBUG = false;

  /** Another way to index into edges: lookup by (relation,argument) */
  public static class Arg {
    public final Relation rel;
    public final HypNode arg;
    public final int hc;
    public Arg(Relation rel, HypNode arg) {
      this.rel = rel;
      this.arg = arg;
      this.hc = Hash.mix(rel.hashCode(), arg.hashCode());
    }
    @Override
    public int hashCode() { return hc; }
    @Override
    public boolean equals(Object other) {
      if (other instanceof Arg) {
        Arg a = (Arg) other;
        return rel == a.rel && arg == a.arg;
      }
      return false;
    }
  }

  private Map<HypNode, LL<HypEdge>> primaryView;
  private Map<Arg, LL<HypEdge>> fineView;

  public State() {
    this.primaryView = new HashMap<>();
    this.fineView = new HashMap<>();
  }

  public void dbgShowEdges() {
    System.out.println("State with " + primaryView.size() + " nodes:");
//    for (Map.Entry<HypNode, LL<HypEdge>> x : adjacencyView1.entrySet()) {
//      System.out.println(x.getKey());
//      for (LL<HypEdge> cur = x.getValue(); cur != null; cur = cur.next)
//        System.out.println("\t" + cur.item);
//    }
    Set<HypEdge> es = new HashSet<>();
    List<HypEdge> el = new ArrayList<>();
    for (LL<HypEdge> l : primaryView.values()) {
      for (LL<HypEdge> cur = l; cur != null; cur = cur.next) {
        if (es.add(cur.item))
          el.add(cur.item);
      }
    }
    try {
//      Collections.sort(el, HypEdge.BY_RELATION);
      Collections.sort(el, HypEdge.BY_RELATION_THEN_TAIL);
    } catch (ClassCastException cce) {
      Log.warn("couldn't sort HypEdges because: " + cce.getMessage());
    }
    for (HypEdge e : el) {
      System.out.println(e);
    }
    System.out.println();
  }

  public void add(HypEdge e) {
    add(e.getHead(), e);
    int n = e.getNumTails();
    for (int i = 0; i < n; i++)
      add(e.getTail(i), e);

    if (DEBUG) {
      Log.info("just added to State: " + e);
      Log.info("Adjacent" + e.getHead() + "\t" + neighbors(e.getHead()));
      for (int i = 0; i < e.getNumTails(); i++) {
        HypNode x = e.getTail(i);
        Log.info("Adjacent" + x + "\t" + neighbors(x));
      }
      System.out.println();
    }
  }

  private void add(HypNode n, HypEdge e) {
    LL<HypEdge> es = primaryView.get(n);
    primaryView.put(n, new LL<>(e, es));

    Arg key = new Arg(e.getRelation(), n);
    es = fineView.get(key);
    fineView.put(key, new LL<>(e, es));
  }

  public LL<HypEdge> match(Relation rel, HypNode arg) {
    return fineView.get(new Arg(rel, arg));
  }

  public List<HypEdge> neighbors(HypNode n) {
    List<HypEdge> el = new ArrayList<>();
    for (LL<HypEdge> cur = primaryView.get(n); cur != null; cur = cur.next)
      el.add(cur.item);
    return el;
  }

  public List<HNode> neighbors(HNode node) {
    List<HNode> a = new ArrayList<>();
    if (node.isLeft()) {
      HypNode n = node.getLeft();
      for (LL<HypEdge> cur = primaryView.get(n); cur != null; cur = cur.next)
        a.add(new HNode(cur.item));
    } else {
      HypEdge e = node.getRight();
      for (HypNode n : e.getNeighbors())
        a.add(new HNode(n));
    }
    return a;
  }
}