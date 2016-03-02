package edu.jhu.hlt.uberts.factor;

import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;

public interface GlobalFactor {
  /** Do whatever you want to the edges in the agenda */
  public void rescore(Agenda a, GraphTraversalTrace match);

  /**
   * Allows you to chain {@link GlobalFactor}s together.
   */
  public static class Composite implements GlobalFactor {
    private GlobalFactor left, right;
    public Composite(GlobalFactor left, GlobalFactor right) {
      this.left = left;
      this.right = right;
    }
    @Override
    public void rescore(Agenda a, GraphTraversalTrace match) {
      left.rescore(a, match);
      right.rescore(a, match);
    }
  }
}