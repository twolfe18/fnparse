package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.tutils.hash.Hash;

/**
 * A hyper-edge in a hyper-graph where the tail represents the columns in a
 * relation and the head is the identity of the row (witness of the fact).
 * See {@link Relation#encodeTail(Object[])} for details on how this head value
 * is constructed.
 *
 * Tail nodes may be type-checked against the relation.
 * For now, the head node is untyped (since it is automatically generated).
 *
 * HypEdges in a graph are assumed to be unique up to (relation,tail). That is
 * we are not using a *multi* hyper-graph, just a hyper-graph. hashCode and
 * equals only look at (relation,tail).
 *
 * @author travis
 */
public class HypEdge {

  // Check tail node types match relation type
  public static boolean TYPE_CHECK = true;

  private Relation relation;    // e.g. 'srl2'
  private HypNode head;         // this node is the witness of the fact
  private HypNode[] tail;       // argument/columns of relation, e.g. ['arg:span', 'pred:span']

  public HypEdge(Relation edgeType, HypNode head, HypNode[] tail) {
    this.relation = edgeType;
    this.head = head;
    this.tail = tail;

    if (TYPE_CHECK) {
      int n = tail.length;
      if (n != relation.getNumArgs())
        throw new IllegalArgumentException("tail=" + Arrays.toString(tail) + " rel.numArgs=" + relation.getNumArgs() + " rel.name=" + relation.getName());
      for (int i = 0; i < n; i++)
        if (edgeType.getTypeForArg(i) != tail[i].getNodeType())
          throw new IllegalArgumentException();
    }
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
//    sb.append("(Edge [");
//    for (int i = 0; i < tail.length; i++) {
//      if (i > 0) sb.append(", ");
//      sb.append(tail[i].toString());
//    }
//    sb.append("] -");
//    sb.append(relation.getName());
//    sb.append("-> ");
//    sb.append(head == null ? "null" : head.toString());
//    sb.append(')');
    sb.append(relation.getName());
    sb.append('(');
    for (int i = 0; i < tail.length; i++) {
      if (i > 0)
        sb.append(", ");
//      sb.append(tail[i].getValue() + ":" + tail[i].getNodeType().getName());
      sb.append(tail[i].getValue());
    }
//    sb.append(", head=" + head.getValue());
    sb.append(")");
    return sb.toString();
  }

  /**
   * Use this class whenever you want to put {@link HypEdge}s into a set/map
   * where hashCode/equals only pays attention to the relation and tail
   * (arguments/columns/values of the relation). This is useful, e.g., when
   * ascribing a label to a {@link HypEdge} by checking its membership in a set
   * of gold edges. I don't want to commit to a particular implementation of
   * hashCode/equals on HypEdge, let alone a non-standard one like described
   * (hashCode/equals IGNORES a field), so this is a nice compromise.
   *
   * Uses == to check {@link Relation} and {@link HypNode} equality.
   */
  public static class HashableHypEdge {
    private HypEdge edge;
    private int hc;
    public HashableHypEdge(HypEdge e) {
      this.edge = e;
      long[] h = new long[e.getNumTails() + 1];
      h[0] = e.getRelation().hashCode();
      for (int i = 1; i < h.length; i++)
        h[i] = e.getTail(i-1).hashCode();
      this.hc = (int) Hash.mix64(h);
    }
    @Override
    public int hashCode() {
      return hc;
    }
    @Override
    public boolean equals(Object other) {
      if (other instanceof HashableHypEdge) {
        HashableHypEdge hhe = (HashableHypEdge) other;
        if (hc != hhe.hc || edge.relation == hhe.edge.relation)
          return false;
        int n = edge.relation.getNumArgs();
        assert n == hhe.edge.relation.getNumArgs();
        for (int i = 0; i < n; i++)
          if (edge.getTail(i) != hhe.edge.getTail(i))
            return false;
        return true;
      }
      return false;
    }
  }

  public static Comparator<HypEdge> BY_RELATION = new Comparator<HypEdge>() {
    @Override
    public int compare(HypEdge o1, HypEdge o2) {
      return Relation.BY_NAME.compare(o1.getRelation(), o2.getRelation());
    }
  };
  /** Lexicographic sort of tail values */
  public static Comparator<HypEdge> BY_RELATION_THEN_TAIL = new Comparator<HypEdge>() {
    @SuppressWarnings("unchecked")
    @Override
    public int compare(HypEdge o1, HypEdge o2) {
      int c = Relation.BY_NAME.compare(o1.getRelation(), o2.getRelation());
      if (c != 0)
        return c;
      int n = o1.tail.length;
      assert n == o2.tail.length;
      for (int i = 0; i < n; i++) {
        Object x1 = o1.tail[i].getValue();
        Object x2 = o2.tail[i].getValue();
        int ci = ((Comparable<Object>) x1).compareTo(x2);
        if (ci != 0)
          return ci;
      }
      return 0;
    }
  };
}