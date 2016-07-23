package edu.jhu.hlt.uberts.features;

import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.tutils.scoring.Adjoints;

/*
 * The problem is that I use AveragedPerceptronWeights.adj
 */
public class DebugFeatureAdj implements Adjoints {

  private boolean showWrapped = false;
  private Adjoints wrapped;

  // These are for debugging
  private List<String> fy;
  private List<String> fx;
  private Object ancillaryInfo;

  public DebugFeatureAdj(Adjoints wrapped, String[] fy, String[] fx, Object ancillaryInfo) {
    this(wrapped, Arrays.asList(fy), Arrays.asList(fx), ancillaryInfo);
  }

  public DebugFeatureAdj(Adjoints wrapped, List<String> fy, List<String> fx, Object ancillaryInfo) {
    this.wrapped = wrapped;
    this.fy = fy;
    this.fx = fx;
    this.ancillaryInfo = ancillaryInfo;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("(DFA");
    if (ancillaryInfo != null)
      sb.append(" " + ancillaryInfo);
    sb.append(" fy=" + fy);
    sb.append(" fx=" + fx);
    if (showWrapped) {
      sb.append(" " + wrapped);
    }
    sb.append(')');
    return sb.toString();
  }

  @Override
  public double forwards() {
    return wrapped.forwards();
  }

  @Override
  public void backwards(double dErr_dForwards) {
    System.out.printf("[DebugFeatureAdj backwards] dErr=%+.1f %s\n", dErr_dForwards, this);
    wrapped.backwards(dErr_dForwards);
  }
}
