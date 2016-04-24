package edu.jhu.hlt.uberts;

import edu.jhu.hlt.tutils.Either;

/**
 * This is the node type in the graph (i.e. not the hyper-graph). This is the
 * data structure which enables the graph view of the {@link State} hyper-graph.
 */
public class HNode extends Either<HypNode, HypEdge> {

  public HNode(HypNode l) {
    super(l, null);
  }

  public HNode(HypEdge r) {
    super(null, r);
  }

  public HypNode getNode() {
    assert isNode();
    return getLeft();
  }

  public boolean isNode() {
    return isLeft();
  }

  public HypEdge getEdge() {
    assert isEdge();
    return getRight();
  }

  public boolean isEdge() {
    return isRight();
  }

  @Override
  public String toString() {
    if (isLeft())
      return "HNode(Node, " + getLeft().toString() + ")";
    else
      return "HNode(Edge, " + getRight().toString() + ")";
  }

  /**
   * Either.equals uses L/R equals, which is not implemented for HypNode and HypEdge.
   * This uses == for both.
   */
  @Override
  public boolean equals(Object other) {
    if (other instanceof HNode) {
      HNode n = (HNode) other;
      if (isLeft() != n.isLeft())
        return false;
      if (isLeft())
        return getLeft() == n.getLeft();
      return getRight() == n.getRight();
    }
    return false;
  }
}