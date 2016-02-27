package edu.jhu.hlt.uberts;

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