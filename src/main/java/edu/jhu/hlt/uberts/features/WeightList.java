package edu.jhu.hlt.uberts.features;

import java.util.ArrayList;

import edu.jhu.hlt.tutils.scoring.Adjoints;

public class WeightList<T> extends ArrayList<Weight<T>> implements Adjoints {
  private static final long serialVersionUID = 6900167289293784476L;

  private int numInstances;
  public boolean useAvg;

  public WeightList(int numInstances, boolean useAvg) {
    super();
    if (useAvg) assert numInstances >= 0;
    this.numInstances = numInstances;
    this.useAvg = useAvg;
  }

  @Override
  public double forwards() {
    double sum = 0;
    if (useAvg) {
      for (Weight<T> w : this)
        sum += w.getAvgWeight(numInstances);
    } else {
      for (Weight<T> w : this)
        sum += w.getWeight();
    }
    return sum;
  }

  @Override
  public void backwards(double dErr_dForwards) {
    if (useAvg) {
      for (Weight<T> w : this)
        w.incrementWithAvg(-dErr_dForwards, numInstances);
    } else {
      for (Weight<T> w : this)
        w.increment(-dErr_dForwards);
    }
  }

}
