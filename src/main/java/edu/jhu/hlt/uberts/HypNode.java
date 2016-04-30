package edu.jhu.hlt.uberts;

import edu.jhu.hlt.tutils.hash.Hash;

/**
 * A hyper-graph node. Stores its type and value.
 *
 * NOTE: Not including hashcode or equals since these should be uniq, with
 * the alphabet maintained by {@link Uberts}.
 *
 * @author travis
 */
public class HypNode {
  private NodeType nt;
  private Object value;
  private int hash;

  // e.g. (tokenIndex, 5)
  public HypNode(NodeType type, Object value) {
    this.nt = type;
    this.value = value;
    this.hash = Hash.mix(type.hashCode(), value.hashCode());
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof HypNode) {
      HypNode n = (HypNode) other;
      return nt == n.nt && value.equals(n.value);
    }
    return false;
  }

  public NodeType getNodeType() {
    return nt;
  }

  public Object getValue() {
    return value;
  }

  @Override
  public String toString() {
    return "(" + nt + " " + value + ")";
  }
}