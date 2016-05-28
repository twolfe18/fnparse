package edu.jhu.hlt.uberts.factor;

import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;

public interface GlobalFactor {

  /** Do whatever you want to the edges in the agenda */
  public void rescore(Agenda a, GraphTraversalTrace match);

  public String getName();

  /**
   * Return a short string representing useful information about this factors
   * use since the last call to this method (or construction).
   * Return null if you have nothing to report.
   * Do not include information about the name of this factor in this string.
   */
  default public String getStats() {
    return null;
  }

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
    public String getName() {
      return "(Comp " + left.getName() + " " + right.getName() + ")";
    }
    @Override
    public void rescore(Agenda a, GraphTraversalTrace match) {
      left.rescore(a, match);
      right.rescore(a, match);
    }
  }
}