package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.log4j.Logger;

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

  public static void showBiggestCounts(List<FeatureWeight> sortedCounts, int k, String desc, Logger log) {
    int n = Math.min(k, sortedCounts.size());
    log.info(desc + " " + k + " most biggest counts:");
    for (int i = 0; i < n; i++)
      log.info(desc + " " + sortedCounts.get(sortedCounts.size() - (i + 1)));
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
    List<String> fn = new ArrayList<>();
    for (int i = 0; i < weights.length; i++)
      fn.add(names.lookupObject(i));
    return getSortedWeights(weights, fn);
  }

  public static List<FeatureWeight> getSortedWeights(double[] weights, List<String> names) {
    List<FeatureWeight> w = new ArrayList<>();
    for (int i = 0; i < weights.length; i++)
      w.add(new FeatureWeight(names.get(i), weights[i]));
    Collections.sort(w);
    return w;
  }
}
