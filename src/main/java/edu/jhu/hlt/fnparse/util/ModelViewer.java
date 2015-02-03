package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.gm.model.FgModel;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.util.Alphabet;

public class ModelViewer {

  public static class FeatureWeight implements Comparable<FeatureWeight> {
    public String name;
    public double weight;

    public FeatureWeight(String name, double weight) {
      this.name = name;
      this.weight = weight;
    }

    @Override
    public int compareTo(FeatureWeight o) {
      if (weight < o.weight)
        return -1;
      if (weight > o.weight)
        return 1;
      return 0;
    }

    @Override
    public String toString() {
      return String.format("%130s %+8.3f", name, weight);
    }
  }

  public static void showBiggestWeights(List<FeatureWeight> sortedWeights, int k, String desc, Logger log) {
    int n = Math.min(k, sortedWeights.size());
    log.info(desc + " " + k + " most negative weights:");
    for (int i = 0; i < n; i++)
      log.info(desc + " " + sortedWeights.get(i));
    log.info(desc + " " + k + " most positive weights:");
    for (int i = 0; i < n; i++)
      log.info(desc + " " + sortedWeights.get(sortedWeights.size() - (i + 1)));
  }

  public static List<FeatureWeight> getSortedWeights(double[] weights, Alphabet<String> names) {
    FgModel model = new FgModel(weights.length);
    model.updateModelFromDoubles(weights);
    return getSortedWeights(model, names);
  }

  public static List<FeatureWeight> getSortedWeights(FgModel weights, Alphabet<String> names) {
    List<FeatureWeight> w = new ArrayList<>();
//    assert names.size() <= weights.getNumParams()
//        : "weights should be able to accomodate all the features in the alphabet!";
    final int n = Math.min(weights.getNumParams(), names.size());
    weights.apply(new FnIntDoubleToDouble() {
      @Override
      public double call(int arg0, double arg1) {
        if (arg0 < n)
          w.add(new FeatureWeight(names.lookupObject(arg0), arg1));
        return arg1;
      }
    });
    Collections.sort(w);
    return w;
  }
}
