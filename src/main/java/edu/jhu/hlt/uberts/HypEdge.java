package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;

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

  public static final long IS_SCHEMA =  1l<<0;
  public static final long IS_X =       1l<<1;
  public static final long IS_Y =       1l<<2;
  public static final long IS_DERIVED = 1l<<3;
  public static class WithProps extends HypEdge {
    private long bitmask;

    public WithProps(HypEdge copy, long bitmask) {
      this(copy.relation, copy.head, copy.tail, bitmask);
    }

    public WithProps(Relation edgeType, HypNode head, HypNode[] tail, long bitmask) {
      super(edgeType, head, tail);
      this.bitmask = bitmask;
    }

    public void setPropertyMask(long propertyMask) {
      bitmask = propertyMask;
    }

    public void setProperty(long propertyMask, boolean value) {
      if (value)
        bitmask |= propertyMask;
      else
        bitmask &= ~propertyMask;
    }

    public boolean hasProperty(long mask) {
      return (bitmask & mask) != 0;
    }

    public long getProperties() {
      return bitmask;
    }
  }

  public static String getRelLineDataType(HypEdge.WithProps e) {
    String dt = null;
    if (e.hasProperty(IS_SCHEMA))
      dt = "schema";
    if (e.hasProperty(IS_Y)) {
      assert dt == null;
      dt = "y";
    }
    if (e.hasProperty(IS_X)) {
      assert dt == null;
      dt = "x";
    }
    return dt;
  }

//  /**
//   * @deprecated TODO Schema should be encoded in Relation, not HypEdge!
//   * An example of why is in {@link TransitionGeneratorBackwardsParser}, where
//   * we are inferring LHS facts (HypEdges) from RHS facts. If we can't do this
//   * because some LHS variables are not bound on the RHS, we should be able to
//   * assert that the LHS {@link Relation} is a schema relation, not just the
//   * fact/HypEdge.
//   *
//   * These edges are persistent in the state, e.g.
//   *   def role2 <frame> <role>
//   *   role2(framenet/Commerce_buy, Buyer)
//   */
//  public static class Schema extends HypEdge {
//    public Schema(Relation edgeType, HypNode head, HypNode[] tail) {
//      super(edgeType, head, tail);
//    }
//    public Schema(HypEdge copy) {
//      super(copy.relation, copy.head, copy.tail);
//    }
//  }

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

  /**
   * Produces a string like "x word2 0 John" representing this edge. Produces
   * tail values by calling toString, so be wary of more complex types.
   * @param dataType e.g. "x"
   */
  public String getRelFileString(String dataType) {
    return getRelLine(dataType).toLine();
  }

  public RelLine getRelLine(String datatype) {
    return getRelLine(datatype, null);
  }
  public RelLine getRelLine(String datatype, String comment) {
    String[] tokens = new String[tail.length + 2];
    tokens[0] = datatype;
    tokens[1] = relation.getName();
    for (int i = 0; i < tail.length; i++)
      tokens[i + 2] = tail[i].getValue().toString();
    return new RelLine(tokens, comment);
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
    public final long hc;

    public HashableHypEdge(HypEdge e) {
      this.edge = e;
      long h = Hash.hash(e.getRelation().getName());
      int n = e.getNumTails();
      for (int i = 0; i < n; i++)
        h = Hash.mix64(h, e.getTail(i).hashCode());
      this.hc = h;
    }

    public String hashDesc() {
      StringBuilder sb = new StringBuilder();
      sb.append(edge.toString());
      sb.append(" hc=" + hc);
      sb.append(" hc(rel)=" + edge.getRelation().hashCode());
      sb.append(" hc(head)=" + edge.getHead().hashCode() + "/" + edge.getHead().getValue().getClass().getName());
      sb.append(" hc(tail)=");
      for (int i = 0; i < edge.getNumTails(); i++) {
        if (i == 0)
          sb.append('[');
        else
          sb.append(", ");
        HypNode t = edge.getTail(i);
        sb.append(t.hashCode());
        sb.append("/" + t.getValue().getClass().getName());
        sb.append("/" + t.getValue());
      }
      sb.append(']');
      return sb.toString();
    }

    public HypEdge getEdge() {
      return edge;
    }

    @Override
    public int hashCode() {
      return (int) hc;
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof HashableHypEdge) {
        HashableHypEdge hhe = (HashableHypEdge) other;
        if (hc != hhe.hc || edge.relation != hhe.edge.relation)
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

    @Override
    public String toString() {
      return "(Hashable " + edge + " hash=" + Long.toHexString(hc).toUpperCase() + ")";
    }

    public static Comparator<HashableHypEdge> BY_RELATION = new Comparator<HashableHypEdge>() {
      @Override
      public int compare(HashableHypEdge o1, HashableHypEdge o2) {
        return HypEdge.BY_RELATION.compare(o1.getEdge(), o2.getEdge());
      }
    };
    public static Comparator<HashableHypEdge> BY_RELATION_THEN_TAIL = new Comparator<HashableHypEdge>() {
      @Override
      public int compare(HashableHypEdge o1, HashableHypEdge o2) {
        return HypEdge.BY_RELATION_THEN_TAIL.compare(o1.getEdge(), o2.getEdge());
      }
    };
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