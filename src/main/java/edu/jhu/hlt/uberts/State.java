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

public class State {

  public static boolean DEBUG = false;

  private Map<HypNode, LL<HypEdge>> adjacencyView1;

  public State() {
    this.adjacencyView1 = new HashMap<>();
  }

  public void dbgShowEdges() {
    System.out.println("State with " + adjacencyView1.size() + " nodes:");
//    for (Map.Entry<HypNode, LL<HypEdge>> x : adjacencyView1.entrySet()) {
//      System.out.println(x.getKey());
//      for (LL<HypEdge> cur = x.getValue(); cur != null; cur = cur.next)
//        System.out.println("\t" + cur.item);
//    }
    Set<HypEdge> es = new HashSet<>();
    List<HypEdge> el = new ArrayList<>();
    for (LL<HypEdge> l : adjacencyView1.values()) {
      for (LL<HypEdge> cur = l; cur != null; cur = cur.next) {
        if (es.add(cur.item))
          el.add(cur.item);
      }
    }
//    Collections.sort(el, HypEdge.BY_RELATION);
    Collections.sort(el, HypEdge.BY_RELATION_THEN_TAIL);
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
    LL<HypEdge> es = adjacencyView1.get(n);
    adjacencyView1.put(n, new LL<>(e, es));
  }

  public List<HypEdge> neighbors(HypNode n) {
    List<HypEdge> el = new ArrayList<>();
    for (LL<HypEdge> cur = adjacencyView1.get(n); cur != null; cur = cur.next)
      el.add(cur.item);
    return el;
  }
  public List<HNode> neighbors(HNode node) {
    List<HNode> a = new ArrayList<>();
    if (node.isLeft()) {
      HypNode n = node.getLeft();
      for (LL<HypEdge> cur = adjacencyView1.get(n); cur != null; cur = cur.next)
        a.add(new HNode(cur.item));
    } else {
      HypEdge e = node.getRight();
      for (HypNode n : e.getNeighbors())
        a.add(new HNode(n));
    }
    return a;
  }
}