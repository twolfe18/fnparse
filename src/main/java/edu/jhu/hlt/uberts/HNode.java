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

  @Override
  public String toString() {
    if (isLeft())
      return "HNode(Node, " + getLeft().toString() + ")";
    else
      return "HNode(Edge, " + getRight().toString() + ")";
  }
}