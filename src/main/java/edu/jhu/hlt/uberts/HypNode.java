package edu.jhu.hlt.uberts;

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

  // e.g. (tokenIndex, 5)
  public HypNode(NodeType type, Object value) {
    this.nt = type;
    this.value = value;
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