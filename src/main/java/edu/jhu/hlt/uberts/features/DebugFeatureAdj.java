package edu.jhu.hlt.uberts.features;

import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.tutils.scoring.Adjoints;

/*
 * The problem is that I use AveragedPerceptronWeights.adj
 */
public class DebugFeatureAdj implements Adjoints {

  public static boolean SHOW_CURRENT_FORWARDS_VALUE = false;

  private boolean showWrapped = false;
  private Adjoints wrapped;

  // These are for debugging
  private List<String> fy;
  private List<String> fx;
  private Object ancillaryInfo;
  private double origScore;

  public DebugFeatureAdj(Adjoints wrapped, String[] fy, String[] fx, Object ancillaryInfo) {
    this(wrapped, Arrays.asList(fy), Arrays.asList(fx), ancillaryInfo);
  }

  public DebugFeatureAdj(Adjoints wrapped, List<String> fy, List<String> fx, Object ancillaryInfo) {
    this.origScore = wrapped.forwards();
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
    } else {
      if (SHOW_CURRENT_FORWARDS_VALUE)
        sb.append(String.format(" s0=%+.2f sN=%+.2f", origScore, wrapped.forwards()));
      else
        sb.append(String.format(" s0=%+.2f", origScore));
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
    if (SHOW_CURRENT_FORWARDS_VALUE)
      System.out.printf("[DebugFeatureAdj backwards] fwd0=%+.2f fwdN=%.2f dErr=%+.1f %s\n", origScore, wrapped.forwards(), dErr_dForwards, this);
    else
      System.out.printf("[DebugFeatureAdj backwards] fwd0=%+.2f dErr=%+.1f %s\n", origScore, dErr_dForwards, this);
    wrapped.backwards(dErr_dForwards);
  }
}
