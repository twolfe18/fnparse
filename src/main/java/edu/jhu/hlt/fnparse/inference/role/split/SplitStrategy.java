package edu.jhu.hlt.fnparse.inference.role.split;

import edu.jhu.hlt.fnparse.datatypes.Frame;

/**
 * TODO write this package...
 * 
 * @author travis
 */
public class SplitStrategy {

  /**
   * Returns true if this role should be predicted using the "predict head then
   * expand it to a span" method (good for roles that are typically short spans)
   * or false if this role should be predicted using the "classify spans" method
   * (good for roles that are typically long spans).
   * 
   * TODO: could include a target Span and Sentence as an argument.
   */
  public boolean shouldClassifySpansDirectly(int role, Frame frame) {
    throw new RuntimeException("implement me");
  }
}
