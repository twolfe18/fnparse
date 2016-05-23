package edu.jhu.hlt.uberts;

import edu.jhu.hlt.tutils.hash.Hash;

/**
 * A directed edge in the state (hyper-)graph between a {@link HypEdge} and a
 * {@link HypNode}.
 *
 * @author travis
 */
public class StateEdge {
  public final HypNode node;
  public final HypEdge edge;
  public final int argPos;        // can be State.HEAD_ARG_POS
  public final boolean node2rel;  // else HypEdge->HypNode

  private HNode source, target;
  private long hash;

  public StateEdge(HypNode node, HypEdge edge, int argPos, boolean node2rel) {
    if (argPos < 0)
      throw new IllegalArgumentException("argPos=" + argPos);
    this.node = node;
    this.edge = edge;
    this.argPos = argPos;
    this.node2rel = node2rel;
    this.hash = Hash.mix64(
        node.hashCode(), edge.hashCode(), argPos, node2rel ? 9001 : 42);
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

  public int hashCode() {
    return (int) hash;
  }

  public boolean equals(Object other) {
    if (other instanceof StateEdge) {
      StateEdge se = (StateEdge) other;
      return hash == se.hash
          && node == se.node
          && edge == se.edge
          && argPos == se.argPos
          && node2rel == se.node2rel;
    }
    return false;
  }
}
