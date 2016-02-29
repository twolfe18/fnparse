package edu.jhu.hlt.uberts;

import edu.jhu.hlt.tutils.Either;

/**
 * This is the node type in the graph (i.e. not the hyper-graph).
 * Mostly sugar.
 */
public class HNode extends Either<HypNode, HypEdge> {

  private int hc;

  public HNode(HypNode l) {
    super(l, null);
    hc = super.hashCode();
  }

  public HNode(HypEdge r) {
    super(null, r);
    hc = super.hashCode();
  }

  @Override
  public int hashCode() {
    return hc;
  }

}