package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A hyper-edge in a hyper graph where the tail represents the columns in a
 * relation and the head is the identity of the row (witness of the fact).
 *
 * Tail nodes may be type-checked against the relatoin.
 * For now, the head node is untyped.
 *
 * @author travis
 */
public class HypEdge {

  // Check tail node types match relation type
  public static boolean TYPE_CHECK = true;

  private Relation relation;    // e.g. 'srl2'
  private HypNode head;         // this node is the witness of the fact, may be null if not needed
  private HypNode[] tail;       // argument/columns of relation, e.g. ['arg:span', 'pred:span']

  // TODO Come up with a way to type-check the head node...
  // Cases I can think of for using head (as opposed to just saying that relation
  // args are just the tail, no need for head) are cases where you can only build
  // off of the head node, e.g. head<-srl2(p,a) allows srl3(p,a,k) to be represented as srl3b(head,k)
  // => head node can be used for partial application (take args from tail -- possibly recursively)

  public HypEdge(Relation edgeType, HypNode head, HypNode[] tail) {
    this.relation = edgeType;
    this.head = head;
    this.tail = tail;

    if (TYPE_CHECK) {
      int n = tail.length;
      if (n != relation.getNumArgs())
        throw new IllegalArgumentException();
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
//    sb.append(", head=" + head.getValue() + ":" + head.getNodeType().getName());
    sb.append(", head=" + head.getValue());
    sb.append(")");
    return sb.toString();
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