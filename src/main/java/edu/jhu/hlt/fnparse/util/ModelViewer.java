package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
      return String.format("%120s %.4f", name, weight);
    }
  }

  public static List<FeatureWeight> getSortedWeights(FgModel weights, Alphabet<String> names) {
    List<FeatureWeight> w = new ArrayList<>();
    weights.apply(new FnIntDoubleToDouble() {
      @Override
      public double call(int arg0, double arg1) {
        w.add(new FeatureWeight(names.lookupObject(arg0), arg1));
        return arg1;
      }
    });
    Collections.sort(w);
    return w;
  }
}
