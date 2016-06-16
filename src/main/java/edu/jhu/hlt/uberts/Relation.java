package edu.jhu.hlt.uberts;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;

import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;

/**
 * A set of tuples. Also a label that goes on {@link HypEdge}s.
 *
 * Uses {@link java.lang.Object}s equals and hashcode since they should be
 * de-dup-ed/alphabetized by {@link Uberts#addEdgeType(Relation)}.
 *
 * @author travis
 */
public class Relation {

  private String name;
  private NodeType[] domain;
  private long hc;

  public Relation(String name, NodeType... domain) {
    this.name = name.intern();
    this.domain = domain;
    for (int i = 0; i < domain.length; i++)
      assert domain[i] != null;

    StringBuilder desc = new StringBuilder();
    desc.append(name);
    for (int i = 0; i < domain.length; i++) {
      desc.append('\t');
      desc.append(domain[i].getName());
    }
    this.hc = Hash.sha256(desc.toString());
  }

  public String getName() {
    return name;
  }

  public int getNumArgs() {
    return domain.length;
  }

  public NodeType getTypeForArg(int i) {
    if (i < 0 || i >= domain.length)
      throw new IllegalArgumentException("this doesn't work for head arg types, i=" + i);
    return domain[i];
  }

  /**
   * Returns the definition string for this relation, e.g.
   * "def word2 <tokenIndex> <word>".
   */
  public String getDefinitionString() {
    StringBuilder sb = new StringBuilder("def ");
    sb.append(name);
    for (int i = 0; i < domain.length; i++) {
      sb.append(' ');
      sb.append('<');
      sb.append(domain[i].getName());
      sb.append('>');
    }
    return sb.toString();
  }

  /**
   * Create an Object which has an equals/hashCode implementation that mimics
   * what {@link Arrays#equals(Object)} would do. This value is used as the
   * value given to the head:HypNode in a HypEdge. This allows instances of
   * {@link HypEdge} who have a tail element which is a head node of another
   * HypEdge to have proper equals/hashCode when used with {@link HashableHypEdge}.
   * This is needed to store gold edges in a {@link HashSet}.
   *
   * The default implementation tuples up these values, but you may want to
   * over-ride with a more efficient implementation based on known types (for
   * example if you have two arguments which are both bounded ints, you could
   * bit-shift them into a single Integer to serve as the encoded Object).
   */
  public Object encodeTail(Object[] tail) {
    return new EqualityArray(tail);
  }
  public Object encodeTail(HypNode[] tail) {
    Object[] tailValues = new Object[tail.length];
    for (int i = 0; i < tailValues.length; i++)
      tailValues[i] = tail[i].getValue();
    return encodeTail(tailValues);
  }
  public static class EqualityArray implements Comparable<EqualityArray> {
    private Object[] tail;
    private long hc;
    public EqualityArray(Object[] tail) {
      this.tail = tail;
      long[] h = new long[tail.length];
      for (int i = 0; i < tail.length; i++)
        h[i] = tail[i].hashCode();
      hc = Hash.mix64(h);
    }
    public Object get(int i) {
      return tail[i];
    }
    public int length() {
      return tail.length;
    }
    @Override
    public boolean equals(Object other) {
      if (other instanceof EqualityArray) {
        EqualityArray ea = (EqualityArray) other;
        return Arrays.equals(tail, ea.tail);
      }
      return false;
    }
    @SuppressWarnings("unchecked")
    @Override
    public int compareTo(EqualityArray o) {
      int dtl = tail.length - o.tail.length;
      if (dtl != 0)
        return dtl;
      for (int i = 0; i < tail.length; i++) {
        int d = ((Comparable<Object>) tail[i]).compareTo(o.tail[i]);
        if (d != 0)
          return d;
      }
      return 0;
    }
    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("(EqArr");
      for (int i = 0; i < tail.length; i++) {
        sb.append(' ');
        sb.append(tail[i]);
      }
      sb.append(')');
      return sb.toString();
    }
    @Override
    public int hashCode() {
      return (int) hc;
    }
  }

  @Override
  public String toString() {
    return "(Relation " + name + ")";
  }

  @Override
  public int hashCode() {
    return (int) hc;
  }
  public long hashCode64() {
    return hc;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof Relation) {
      Relation r = (Relation) other;
      return hc == r.hc && name.equals(r.name) &&
          Arrays.asList(domain).equals(Arrays.asList(r.domain));
    }
    return false;
  }

  public static Comparator<Relation> BY_NAME = new Comparator<Relation>() {
    @Override
    public int compare(Relation o1, Relation o2) {
      return o1.getName().compareTo(o2.getName());
    }
  };
}
