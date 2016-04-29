package edu.jhu.hlt.uberts.features;

import java.util.List;
import java.util.Map;

import edu.jhu.hlt.tutils.scoring.Adjoints;

public class WeightAdjoints<T> implements Adjoints {
  private List<T> fx;
  private Map<T, Weight<T>> theta;

  public WeightAdjoints(List<T> features, Map<T, Weight<T>> weights) {
    this.fx = features;
    this.theta = weights;
  }

  public List<T> getFeatures() {
    return fx;
  }

  @Override
  public double forwards() {
    double s = 0;
    for (T index : fx) {
      Weight<T> w = theta.get(index);
      if (w != null)
        s += w.theta;
    }
    return s;
  }

  @Override
  public void backwards(double dErr_dForwards) {
    for (T index : fx) {
      Weight<T> w = theta.get(index);
      if (w == null) {
        w = new Weight<>(index);
        theta.put(index, w);
      }
      w.increment(-dErr_dForwards);
    }
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("(Adj");
    for (T index : fx) {
      Weight<T> w = theta.get(index);
      if (w == null)
        w = new Weight<>(index);
      sb.append(' ');
      sb.append(w.toString());
      if (sb.length() > 200) {
        sb.append("...");
        break;
      }
    }
    sb.append(')');
    return sb.toString();
  }
}
