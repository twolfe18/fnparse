package edu.jhu.hlt.uberts;

public class StateEdge {
  public final HypNode node;
  public final HypEdge edge;
  public final int argPos;        // can be State.HEAD_ARG_POS
  public final boolean node2rel;  // else HypEdge->HypNode
  private HNode source, target;

  public StateEdge(HypNode node, HypEdge edge, int argPos, boolean node2rel) {
    if (argPos < 0)
      throw new IllegalArgumentException("argPos=" + argPos);
    this.node = node;
    this.edge = edge;
    this.argPos = argPos;
    this.node2rel = node2rel;
  }

  public HNode getTarget() {
    if (target == null)
      target = node2rel ? new HNode(edge) : new HNode(node);
    return target;
  }

  public HNode getSource() {
    if (source == null)
      source = node2rel ? new HNode(node) : new HNode(edge);
    return source;
  }

  public String toString() {
    return getSource() + " --arg" + argPos + "--> " + getTarget();
  }
}
