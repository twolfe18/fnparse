package edu.jhu.hlt.fnparse.rl.params;

public interface HasUpdate {

  /**
   * Equivalent to:
   * addTo += scale * this
   */
  public void getUpdate(double[] addTo, double scale);
}
