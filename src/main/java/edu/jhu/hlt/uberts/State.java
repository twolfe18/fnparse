package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.tutils.LL;

public class State {
  private Map<HypNode, LL<HypEdge>> adjacencyView1;
  public State() {
    this.adjacencyView1 = new HashMap<>();
  }
  public void add(HypEdge e) {
    add(e.getHead(), e);
    int n = e.getNumTails();
    for (int i = 0; i < n; i++)
      add(e.getTail(i), e);
  }
  private void add(HypNode n, HypEdge e) {
    LL<HypEdge> es = adjacencyView1.get(n);
    adjacencyView1.put(n, new LL<>(e, es));
  }
  public LL<HypEdge> getEdges(HypNode n) {
    return adjacencyView1.get(n);
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