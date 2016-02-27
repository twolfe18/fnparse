package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.List;

public class HypEdge {
  private Relation relation;    // e.g. 'srl2'
  private HypNode head;
  private HypNode[] tail;       // e.g. ['arg:span', 'pred:span']
  public HypEdge(Relation edgeType, HypNode head, HypNode[] tail) {
    this.relation = edgeType;
    this.head = head;
    this.tail = tail;
  }
  public Relation getRelation() {
    return relation;
  }
  public HypNode getHead() {
    return head;
  }
  public int getNumTails() {
    return tail.length;
  }
  public HypNode getTail(int i) {
    return tail[i];
  }
  public Iterable<HypNode> getNeighbors() {
    List<HypNode> n = new ArrayList<>();
    n.add(head);
    for (HypNode t : tail)
      n.add(t);
    return n;
  }
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("(Edge [");
    for (int i = 0; i < tail.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(tail[i].toString());
    }
    sb.append("] -");
    sb.append(relation.getName());
    sb.append("-> ");
    sb.append(head.toString());
    sb.append(')');
    return sb.toString();
  }
}