package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import edu.jhu.prim.tuple.Pair;

/**
 * Used to find a sensible recallBias parameter value. Starts at 0 and walks out
 * while refining its guess as to where the max is.
 *
 * @author travis
 */
public class ThresholdFinder {

  /**
   * Start with a list of probed points, initialize to have 2 probed items.
   * Find the (i,i+1) which sum to the max.
   * If i == 0: probe left
   * Else If i+1 == list.size-1: probe right
   * Else probe between i and i+1.
   */
  public static Pair<Double, Double> search(Function<Double, Double> probeThreshold, double initLeft, double initRight, int maxCalls) {
    if (initLeft >= initRight)
      throw new IllegalArgumentException();

    // Run maxCalls+2 probes
    List<Pair<Double, Double>> probes = new ArrayList<>();
    probes.add(new Pair<>(initLeft, probeThreshold.apply(initLeft)));
    probes.add(new Pair<>(initRight, probeThreshold.apply(initRight)));
    for (int i = 0; i < maxCalls; i++) {
      step(probeThreshold, probes);
    }

    // Find the best threshold
    double bestPerf = 0;
    int bestPerfIdx = 0;
    for (int i = 0; i < probes.size(); i++) {
      double perf = probes.get(i).get2();
      if (i == 0 || perf > bestPerf) {
        bestPerf = perf;
        bestPerfIdx = i;
      }
    }
    return probes.get(bestPerfIdx);
  }

  /**
   * @param probes first item is a probe location and second is a prove value.
   */
  private static void step(Function<Double, Double> thresholdEvaluator, List<Pair<Double, Double>> probes) {
    int n = probes.size();

    // Find the best pair
    double maxSum = 0;
    int maxSumIdx = 0;
    for (int i = 0; i < n - 1; i++) {
      double sum = probes.get(i).get2() + probes.get(i+1).get2();
      if (i == 0 || sum > maxSum) {
        maxSumIdx = i;
        maxSum = sum;
      }
    }

    if ((probes.size() > 2 && maxSumIdx == 0)
        || (probes.size() == 1 && probes.get(0).get2() > probes.get(1).get2())) {
      // Expand left
      double d = probes.get(1).get1() - probes.get(0).get1();
      double l = probes.get(0).get1() - d;
      double v = thresholdEvaluator.apply(l);
      probes.add(0, new Pair<>(l, v));
    } else if ((probes.size() > 2 && maxSumIdx+1 == n-1)
        || (probes.size() == 2 && probes.get(1).get2() > probes.get(0).get2())) {
      // Expand right
      double d = probes.get(n-1).get1() - probes.get(n-2).get1();
      double r = probes.get(n-1).get1() + d;
      double v = thresholdEvaluator.apply(r);
      probes.add(new Pair<>(r, v));
    } else {
      // Expand between the best pair
      double l = probes.get(maxSumIdx).get1();
      double r = probes.get(maxSumIdx+1).get1();
      double m = (r - l) / 2 + l;
      double v = thresholdEvaluator.apply(m);
      probes.add(maxSumIdx + 1, new Pair<>(m, v));
    }
  }

  public static void main(String[] args) {
    test(-1.2);
    test(3);
    test(5);
    test(0);
    test(-0.5);
  }

  // Concave quadratic function with max at center
  public static double test(double center) {
    Function<Double, Double> perf = new Function<Double, Double>() {
      @Override
      public Double apply(Double t) {
        //System.out.println("being probed: " + t);
        double d = (center - t);
        return 10 - d*d;
      }
    };
    for (double d = -3; d < 3; d += 0.1)
      System.out.println(d + " \t " + perf.apply(d));

    double best = ThresholdFinder.search(perf, -.5, .5, 10).get1();
    System.out.println("for center=" + center + " best=" + best);
    return best;
  }
}
