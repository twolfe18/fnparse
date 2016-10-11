package edu.jhu.hlt.uberts.factor;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.Uberts;

public interface GlobalFactor {

  /** Do whatever you want to the edges in the agenda */
  public void rescore(Uberts u, HypEdge[] trigger);

  /**
   * Return the names of all of the relations which have fact
   * scores either read or written by this global factor.
   */
  public Set<String> isSenstiveToLabelsFromRelations();

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
  
  public void writeWeightsTo(File f);
  public void readWeightsFrom(File f);
  

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
    public void rescore(Uberts u, HypEdge[] trigger) {
      left.rescore(u, trigger);
      right.rescore(u, trigger);
    }
    @Override
    public void writeWeightsTo(File f) {
      throw new RuntimeException("can't write two models to one file!");
    }
    @Override
    public void readWeightsFrom(File f) {
      throw new RuntimeException("can't read two models from one file!");
    }
    public Set<String> isSenstiveToLabelsFromRelations() {
      Set<String> u = new HashSet<>();
      u.addAll(left.isSenstiveToLabelsFromRelations());
      u.addAll(right.isSenstiveToLabelsFromRelations());
      return u;
    }
  }
}