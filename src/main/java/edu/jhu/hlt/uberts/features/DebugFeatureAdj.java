package edu.jhu.hlt.uberts.features;

import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;

/**
 * Wraps another {@link Adjoints} but maintains lists of label (fy) and
 * data (fx) features which are shown when backwards is called.
 *
 * @author travis
 */
public class DebugFeatureAdj implements Adjoints {

  public static boolean SHOW_CURRENT_FORWARDS_VALUE = true;

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
      if (SHOW_CURRENT_FORWARDS_VALUE) {

//        if (ancillaryInfo != null && "argument4(2-3, framenet/Omen, 0-1, Outcome)".equals(ancillaryInfo.toString()) && wrapped.forwards() == 0) {
//          Log.info("maybe interesting?");
//          wrapped.forwards();
//        }

        sb.append(String.format(" s0=%+.2f sN=%+.2f", origScore, wrapped.forwards()));
      } else {
        sb.append(String.format(" s0=%+.2f", origScore));
      }
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
